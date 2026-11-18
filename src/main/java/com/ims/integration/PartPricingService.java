package com.ims.integration;

import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.company.CompanyService;
import com.ims.company.CurrencyService;
import com.ims.part.BomService;
import com.ims.part.Part;
import com.ims.part.PartService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiresFeature(Feature.PRICING_ROLLUP)
public class PartPricingService {

    public record BomCostComponent(
            UUID partId,
            String ipn,
            String name,
            BigDecimal quantity,
            BigDecimal unitMinCost,
            BigDecimal unitMaxCost,
            BigDecimal extendedMinCost,
            BigDecimal extendedMaxCost,
            String source
    ) {}

    public record PartPricingView(
            UUID partId,
            String partIpn,
            String partName,
            String currency,
            BigDecimal supplierMinUnitCost,
            BigDecimal supplierMaxUnitCost,
            BigDecimal bomMinUnitCost,
            BigDecimal bomMaxUnitCost,
            BigDecimal effectiveMinUnitCost,
            BigDecimal effectiveMaxUnitCost,
            List<BomCostComponent> bomBreakdown
    ) {}

    private final PartService partService;
    private final BomService bomService;
    private final CompanyService companyService;
    private final CurrencyService currencyService;

    public PartPricingService(
            PartService partService,
            BomService bomService,
            CompanyService companyService,
            CurrencyService currencyService
    ) {
        this.partService = partService;
        this.bomService = bomService;
        this.companyService = companyService;
        this.currencyService = currencyService;
    }

    @Transactional(readOnly = true)
    public PartPricingView computePricing(UUID partId, BigDecimal quantity) {
        BigDecimal qty = (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0) ? quantity : BigDecimal.ONE;
        String baseCurrency = currencyService.getTenantBaseCurrency();
        return computeRecursive(partId, qty, baseCurrency, new HashSet<>());
    }

    private PartPricingView computeRecursive(UUID partId, BigDecimal qty, String baseCurrency, Set<UUID> visited) {
        Part part = partService.requirePart(partId);
        List<CompanyService.SupplierPartView> supplierParts = companyService.listSupplierPartsForPart(partId);

        BigDecimal supMin = null;
        BigDecimal supMax = null;
        for (var sp : supplierParts) {
            for (var pb : sp.priceBreaks()) {
                if (qty.compareTo(pb.minQuantity()) >= 0 || sp.priceBreaks().size() == 1) {
                    BigDecimal unit = pb.convertedInternalUnitPriceInBase();
                    if (unit != null) {
                        supMin = supMin == null ? unit : supMin.min(unit);
                        supMax = supMax == null ? unit : supMax.max(unit);
                    }
                }
            }
            if (supMin == null && !sp.priceBreaks().isEmpty()) {
                BigDecimal unit = sp.priceBreaks().get(0).convertedInternalUnitPriceInBase();
                if (unit != null) {
                    supMin = unit;
                    supMax = unit;
                }
            }
        }

        BigDecimal bomMin = null;
        BigDecimal bomMax = null;
        List<BomCostComponent> breakdown = new ArrayList<>();

        if (part.isAssembly() && visited.add(partId)) {
            try {
                List<BomService.BomItemView> bomItems = bomService.listBomItems(partId);
                if (!bomItems.isEmpty()) {
                    BigDecimal sumMin = BigDecimal.ZERO;
                    BigDecimal sumMax = BigDecimal.ZERO;
                    for (var bi : bomItems) {
                        if (bi.optional()) continue;
                        PartPricingView sub = computeRecursive(bi.subPartId(), bi.quantity().multiply(qty), baseCurrency, visited);
                        BigDecimal uMin = sub.effectiveMinUnitCost() != null ? sub.effectiveMinUnitCost() : BigDecimal.ZERO;
                        BigDecimal uMax = sub.effectiveMaxUnitCost() != null ? sub.effectiveMaxUnitCost() : BigDecimal.ZERO;
                        BigDecimal extMin = uMin.multiply(bi.quantity()).setScale(6, RoundingMode.HALF_UP);
                        BigDecimal extMax = uMax.multiply(bi.quantity()).setScale(6, RoundingMode.HALF_UP);
                        sumMin = sumMin.add(extMin);
                        sumMax = sumMax.add(extMax);
                        breakdown.add(new BomCostComponent(
                                bi.subPartId(),
                                bi.subPartIpn(),
                                bi.subPartName(),
                                bi.quantity(),
                                uMin,
                                uMax,
                                extMin,
                                extMax,
                                sub.bomMinUnitCost() != null ? "BOM_ROLLUP" : (sub.supplierMinUnitCost() != null ? "SUPPLIER" : "NONE")
                        ));
                    }
                    bomMin = sumMin;
                    bomMax = sumMax;
                }
            } finally {
                visited.remove(partId);
            }
        }

        BigDecimal effMin = bomMin != null ? bomMin : supMin;
        BigDecimal effMax = bomMax != null ? bomMax : supMax;

        return new PartPricingView(
                part.getId(),
                part.getIpn(),
                part.getName(),
                baseCurrency,
                supMin,
                supMax,
                bomMin,
                bomMax,
                effMin,
                effMax,
                breakdown
        );
    }
}
