package com.ims.stock.application;

import com.ims.stock.domain.model.*;
import com.ims.stock.infrastructure.persistence.*;
import com.ims.shared.exception.*;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.ims.catalog.spi.CatalogReadPort;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.partner.spi.PartnerReadPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates QR / DataMatrix / Code128 barcodes using ZXing and resolves scanned barcode strings
 * to StockItems, StockLocations, or Parts.
 */
@Service
@RequiresFeature(Feature.BARCODES)
public class BarcodeService {

    public record ScanResolutionResult(
            String matchedType, // STOCK_ITEM, STOCK_LOCATION, PART
            UUID entityId,
            String code,
            String title,
            String subtitle,
            String href,
            Object detail
    ) {}

    private final StockItemRepository stockItemRepository;
    private final StockLocationRepository locationRepository;
    private final StockService stockService;
    private final StockLocationService locationService;
    private final CatalogReadPort catalogReadPort;
    private final PartnerReadPort partnerReadPort;

    public BarcodeService(
            StockItemRepository stockItemRepository,
            StockLocationRepository locationRepository,
            StockService stockService,
            StockLocationService locationService,
            CatalogReadPort catalogReadPort,
            PartnerReadPort partnerReadPort
    ) {
        this.stockItemRepository = stockItemRepository;
        this.locationRepository = locationRepository;
        this.stockService = stockService;
        this.locationService = locationService;
        this.catalogReadPort = catalogReadPort;
        this.partnerReadPort = partnerReadPort;
    }

    public byte[] generateBarcodePng(String payload, String format, int width, int height) {
        if (payload == null || payload.isBlank()) {
            throw new ValidationException("Barcode payload is required");
        }
        BarcodeFormat zxingFormat = switch (format != null ? format.trim().toUpperCase(Locale.ROOT) : "QR_CODE") {
            case "CODE_128", "CODE128" -> BarcodeFormat.CODE_128;
            case "DATA_MATRIX", "DATAMATRIX" -> BarcodeFormat.DATA_MATRIX;
            default -> BarcodeFormat.QR_CODE;
        };
        int w = Math.clamp(width, 64, 1024);
        int h = Math.clamp(height, 64, 1024);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BitMatrix matrix = new MultiFormatWriter().encode(payload.trim(), zxingFormat, w, h);
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ValidationException("Failed to render barcode: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public ScanResolutionResult resolveScan(String rawBarcode) {
        if (rawBarcode == null || rawBarcode.isBlank()) {
            throw new ValidationException("Scanned barcode must not be blank");
        }
        String code = rawBarcode.trim();

        // 1. Structured prefix format: IMS:STOCK:<uuid>, IMS:LOC:<uuid>, IMS:PART:<uuid>
        String upper = code.toUpperCase(Locale.ROOT);
        if (upper.startsWith("IMS:STOCK:")) {
            UUID id = parseUuidOr404(code.substring("IMS:STOCK:".length()));
            StockService.StockItemView item = stockService.getStockItem(id);
            return toStockItemResult(code, item);
        }
        if (upper.startsWith("IMS:LOC:")) {
            UUID id = parseUuidOr404(code.substring("IMS:LOC:".length()));
            StockLocationService.LocationView loc = locationService.getLocation(id);
            return toLocationResult(code, loc);
        }
        if (upper.startsWith("IMS:PART:")) {
            UUID id = parseUuidOr404(code.substring("IMS:PART:".length()));
            CatalogReadPort.PartSummary part = catalogReadPort.requirePartSummary(id);
            return toPartResult(code, part);
        }

        // 2. Match customBarcode on StockItem
        Optional<StockItem> byStockCustom = stockItemRepository.findByCustomBarcode(code);
        if (byStockCustom.isPresent()) {
            return toStockItemResult(code, stockService.enrichSingleStockItem(byStockCustom.get()));
        }

        // 3. Match customBarcode on StockLocation
        Optional<StockLocation> byLocCustom = locationRepository.findByCustomBarcode(code);
        if (byLocCustom.isPresent()) {
            return toLocationResult(code, locationService.getLocation(byLocCustom.get().getId()));
        }

        // 3b. Match customBarcode on SupplierPart (Phase 3)
        Optional<UUID> bySupplierBarcode = partnerReadPort.findSupplierPartIdByBarcode(code);
        if (bySupplierBarcode.isPresent()) {
            PartnerReadPort.SupplierPartSummary sp = partnerReadPort.getSupplierPart(bySupplierBarcode.get());
            PartnerReadPort.CompanySummary sup = partnerReadPort.requireCompanySummary(sp.supplierId());
            CatalogReadPort.PartSummary part = catalogReadPort.requirePartSummary(sp.partId());
            return new ScanResolutionResult(
                    "SUPPLIER_PART",
                    sp.id(),
                    code,
                    sup.name() + " SKU " + sp.sku(),
                    part.ipn() + " · Pack of " + sp.packQuantity() + " " + part.unit(),
                    "/parts/" + sp.partId(),
                    sp
            );
        }

        // 4. Direct UUID match (StockItem -> StockLocation -> Part)
        try {
            UUID id = UUID.fromString(code);
            Optional<StockItem> si = stockItemRepository.findById(id);
            if (si.isPresent()) {
                return toStockItemResult(code, stockService.enrichSingleStockItem(si.get()));
            }
            Optional<StockLocation> sl = locationRepository.findById(id);
            if (sl.isPresent()) {
                return toLocationResult(code, locationService.getLocation(sl.get().getId()));
            }
        } catch (IllegalArgumentException ignored) {
        }

        // 5. Match by Part IPN
        List<CatalogReadPort.PartSummary> matchingParts = catalogReadPort.searchPartsByKeyword(code, 5);
        Optional<CatalogReadPort.PartSummary> exactIpn = matchingParts.stream()
                .filter(p -> p.ipn().equalsIgnoreCase(code))
                .findFirst();
        if (exactIpn.isPresent()) {
            return toPartResult(code, exactIpn.get());
        }

        throw new ResourceNotFoundException("No stock item, location, or part matched barcode: " + code);
    }

    private UUID parseUuidOr404(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Invalid barcode UUID identifier: " + raw);
        }
    }

    private ScanResolutionResult toStockItemResult(String code, StockService.StockItemView item) {
        String subtitle = (item.serialNumber() != null ? "SN " + item.serialNumber() : "Qty " + item.quantity() + " " + item.partUnit())
                + (item.locationPathString() != null ? " ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â· " + item.locationPathString() : "");
        return new ScanResolutionResult(
                "STOCK_ITEM",
                item.id(),
                code,
                item.partIpn() + " ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â " + item.partName(),
                subtitle,
                "/stock?itemId=" + item.id(),
                item
        );
    }

    private ScanResolutionResult toLocationResult(String code, StockLocationService.LocationView loc) {
        return new ScanResolutionResult(
                "STOCK_LOCATION",
                loc.id(),
                code,
                loc.name() + " (" + loc.locationType() + ")",
                loc.pathString(),
                "/stock?locationId=" + loc.id(),
                loc
        );
    }

    private ScanResolutionResult toPartResult(String code, CatalogReadPort.PartSummary part) {
        return new ScanResolutionResult(
                "PART",
                part.id(),
                code,
                part.ipn() + " · " + part.name(),
                "Rev " + part.revision() + " · Unit: " + part.unit(),
                "/parts/" + part.id(),
                part
        );
    }
}
