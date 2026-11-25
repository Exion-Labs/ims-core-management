package com.ims.partner.application;

import com.ims.partner.domain.model.Company;
import com.ims.partner.domain.model.SupplierPart;
import com.ims.partner.infrastructure.persistence.CompanyRepository;
import com.ims.partner.infrastructure.persistence.SupplierPartRepository;
import com.ims.partner.spi.PartnerReadPort;
import com.ims.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PartnerQueryService implements PartnerReadPort {

    private final CompanyRepository companyRepository;
    private final SupplierPartRepository supplierPartRepository;
    private final CompanyService companyService;

    public PartnerQueryService(
            CompanyRepository companyRepository,
            SupplierPartRepository supplierPartRepository,
            CompanyService companyService
    ) {
        this.companyRepository = companyRepository;
        this.supplierPartRepository = supplierPartRepository;
        this.companyService = companyService;
    }

    @Override
    @Transactional(readOnly = true)
    public CompanySummary requireCompanySummary(UUID companyId) {
        Company c = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found: " + companyId));
        return toCompanySummary(c);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CompanySummary> getCompanySummaries(Collection<UUID> companyIds) {
        Map<UUID, CompanySummary> result = new HashMap<>();
        if (companyIds == null || companyIds.isEmpty()) {
            return result;
        }
        for (Company c : companyRepository.findAllById(companyIds)) {
            result.put(c.getId(), toCompanySummary(c));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierPartSummary getSupplierPart(UUID supplierPartId) {
        return toSupplierPartSummary(companyService.getSupplierPart(supplierPartId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, SupplierPartSummary> getSupplierPartSummaries(Collection<UUID> supplierPartIds) {
        Map<UUID, SupplierPartSummary> result = new HashMap<>();
        if (supplierPartIds == null || supplierPartIds.isEmpty()) {
            return result;
        }
        for (SupplierPart sp : supplierPartRepository.findAllById(supplierPartIds)) {
            result.put(sp.getId(), new SupplierPartSummary(
                    sp.getId(), sp.getPartId(), sp.getSupplierId(), sp.getSku(),
                    sp.getPackQuantity(), sp.getCurrency(), List.of()
            ));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierPartSummary> listSupplierPartsForPart(UUID partId) {
        return companyService.listSupplierPartsForPart(partId).stream()
                .map(this::toSupplierPartSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupplierPriceBreakSummary> resolveBestPriceBreak(UUID supplierPartId, BigDecimal quantity) {
        return companyService.resolveBestPriceBreak(supplierPartId, quantity)
                .map(this::toPriceBreakSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findSupplierPartIdByBarcode(String barcode) {
        return companyService.findByCustomBarcode(barcode)
                .map(CompanyService.SupplierPartView::id);
    }

    private CompanySummary toCompanySummary(Company c) {
        return new CompanySummary(
                c.getId(), c.getName(), c.getCode(), c.getDefaultCurrency(),
                c.isSupplier(), c.isManufacturer(), c.isCustomer(),
                c.isInternalCompany(), c.isActive()
        );
    }

    private SupplierPartSummary toSupplierPartSummary(CompanyService.SupplierPartView v) {
        List<SupplierPriceBreakSummary> breaks = v.priceBreaks() == null ? List.of()
                : v.priceBreaks().stream().map(this::toPriceBreakSummary).toList();
        return new SupplierPartSummary(
                v.id(), v.partId(), v.supplierId(), v.sku(),
                v.packQuantity(), v.currency(), breaks
        );
    }

    private SupplierPriceBreakSummary toPriceBreakSummary(CompanyService.SupplierPriceBreakView pb) {
        return new SupplierPriceBreakSummary(
                pb.id(), pb.minQuantity(), pb.unitPrice(), pb.currency(), pb.convertedInternalUnitPriceInBase()
        );
    }
}
