package com.ims.partner.spi;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public read port for Companies, Supplier Parts, and Price Breaks.
 * Never exposes JPA @Entity instances outside com.ims.partner.
 */
public interface PartnerReadPort {

    record CompanySummary(
            UUID id,
            String name,
            String code,
            String defaultCurrency,
            boolean supplier,
            boolean manufacturer,
            boolean customer,
            boolean internalCompany,
            boolean active
    ) {}

    record SupplierPriceBreakSummary(
            UUID id,
            BigDecimal minQuantity,
            BigDecimal unitPrice,
            String currency,
            BigDecimal convertedInternalUnitPriceInBase
    ) {}

    record SupplierPartSummary(
            UUID id,
            UUID partId,
            UUID supplierId,
            String sku,
            BigDecimal packQuantity,
            String currency,
            List<SupplierPriceBreakSummary> priceBreaks
    ) {}

    CompanySummary requireCompanySummary(UUID companyId);

    Map<UUID, CompanySummary> getCompanySummaries(Collection<UUID> companyIds);

    SupplierPartSummary getSupplierPart(UUID supplierPartId);

    Map<UUID, SupplierPartSummary> getSupplierPartSummaries(Collection<UUID> supplierPartIds);

    List<SupplierPartSummary> listSupplierPartsForPart(UUID partId);

    Optional<SupplierPriceBreakSummary> resolveBestPriceBreak(UUID supplierPartId, BigDecimal quantity);

    Optional<UUID> findSupplierPartIdByBarcode(String barcode);
}
