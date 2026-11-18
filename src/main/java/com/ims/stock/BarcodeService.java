package com.ims.stock;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.company.CompanyService;
import com.ims.part.Part;
import com.ims.part.PartService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private final PartService partService;
    private final CompanyService companyService;

    public BarcodeService(
            StockItemRepository stockItemRepository,
            StockLocationRepository locationRepository,
            StockService stockService,
            StockLocationService locationService,
            PartService partService,
            CompanyService companyService
    ) {
        this.stockItemRepository = stockItemRepository;
        this.locationRepository = locationRepository;
        this.stockService = stockService;
        this.locationService = locationService;
        this.partService = partService;
        this.companyService = companyService;
    }

    public byte[] generateBarcodePng(String payload, String format, int width, int height) {
        if (payload == null || payload.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Barcode payload is required");
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to render barcode: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public ScanResolutionResult resolveScan(String rawBarcode) {
        if (rawBarcode == null || rawBarcode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scanned barcode must not be blank");
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
            PartService.PartView part = partService.getPart(id);
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
        Optional<CompanyService.SupplierPartView> bySupplierBarcode = companyService.findByCustomBarcode(code);
        if (bySupplierBarcode.isPresent()) {
            CompanyService.SupplierPartView sp = bySupplierBarcode.get();
            return new ScanResolutionResult(
                    "SUPPLIER_PART",
                    sp.id(),
                    code,
                    sp.supplierName() + " SKU " + sp.sku(),
                    sp.partIpn() + " · Pack of " + sp.packQuantity() + " " + sp.partUnit(),
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
        List<Part> matchingParts = partService.searchPartsByKeyword(code, 5);
        Optional<Part> exactIpn = matchingParts.stream()
                .filter(p -> p.getIpn().equalsIgnoreCase(code))
                .findFirst();
        if (exactIpn.isPresent()) {
            return toPartResult(code, partService.getPart(exactIpn.get().getId()));
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No stock item, location, or part matched barcode: " + code);
    }

    private UUID parseUuidOr404(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid barcode UUID identifier: " + raw);
        }
    }

    private ScanResolutionResult toStockItemResult(String code, StockService.StockItemView item) {
        String subtitle = (item.serialNumber() != null ? "SN " + item.serialNumber() : "Qty " + item.quantity() + " " + item.partUnit())
                + (item.locationPathString() != null ? " · " + item.locationPathString() : "");
        return new ScanResolutionResult(
                "STOCK_ITEM",
                item.id(),
                code,
                item.partIpn() + " — " + item.partName(),
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

    private ScanResolutionResult toPartResult(String code, PartService.PartView part) {
        return new ScanResolutionResult(
                "PART",
                part.id(),
                code,
                part.ipn() + " · " + part.name(),
                "Rev " + part.revision() + " · " + (part.categoryPathString() != null ? part.categoryPathString() : "Uncategorized"),
                "/parts/" + part.id(),
                part
        );
    }
}
