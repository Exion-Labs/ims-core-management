package com.ims.company;

import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.part.Part;
import com.ims.part.PartService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiresFeature(Feature.COMPANIES)
public class CompanyService {

    public record CompanyView(
            UUID id,
            String name,
            String code,
            String description,
            String website,
            String email,
            String phone,
            String taxId,
            String defaultCurrency,
            boolean supplier,
            boolean manufacturer,
            boolean customer,
            boolean internalCompany,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record ContactView(
            UUID id,
            UUID companyId,
            String name,
            String roleTitle,
            String email,
            String phone,
            boolean primaryContact
    ) {}

    public record AddressView(
            UUID id,
            UUID companyId,
            String label,
            String line1,
            String line2,
            String city,
            String stateRegion,
            String postalCode,
            String country,
            boolean primaryShipping,
            boolean primaryBilling
    ) {}

    public record ManufacturerPartView(
            UUID id,
            UUID partId,
            String partIpn,
            String partName,
            UUID manufacturerId,
            String manufacturerName,
            String mpn,
            String description,
            String datasheetUrl,
            Instant createdAt
    ) {}

    public record SupplierPriceBreakView(
            UUID id,
            UUID supplierPartId,
            BigDecimal minQuantity,
            BigDecimal unitPrice,
            String currency,
            String tenantBaseCurrency,
            BigDecimal convertedPackPriceInBase,
            BigDecimal convertedInternalUnitPriceInBase
    ) {}

    public record SupplierPartView(
            UUID id,
            UUID partId,
            String partIpn,
            String partName,
            String partUnit,
            UUID supplierId,
            String supplierName,
            UUID manufacturerPartId,
            String manufacturerMpn,
            String sku,
            String description,
            BigDecimal packQuantity,
            BigDecimal moq,
            BigDecimal multiple,
            Integer leadTimeDays,
            String currency,
            String customBarcode,
            boolean active,
            List<SupplierPriceBreakView> priceBreaks,
            Instant createdAt
    ) {}

    public record CreateCompanyCommand(
            String name,
            String code,
            String description,
            String website,
            String email,
            String phone,
            String taxId,
            String defaultCurrency,
            Boolean supplier,
            Boolean manufacturer,
            Boolean customer,
            Boolean internalCompany,
            Boolean active
    ) {}

    public record CreateContactCommand(
            String name,
            String roleTitle,
            String email,
            String phone,
            Boolean primaryContact
    ) {}

    public record CreateAddressCommand(
            String label,
            String line1,
            String line2,
            String city,
            String stateRegion,
            String postalCode,
            String country,
            Boolean primaryShipping,
            Boolean primaryBilling
    ) {}

    public record CreateManufacturerPartCommand(
            UUID partId,
            UUID manufacturerId,
            String mpn,
            String description,
            String datasheetUrl
    ) {}

    public record CreateSupplierPartCommand(
            UUID partId,
            UUID supplierId,
            UUID manufacturerPartId,
            String sku,
            String description,
            BigDecimal packQuantity,
            BigDecimal moq,
            BigDecimal multiple,
            Integer leadTimeDays,
            String currency,
            String customBarcode
    ) {}

    public record UpsertPriceBreakCommand(
            BigDecimal minQuantity,
            BigDecimal unitPrice,
            String currency
    ) {}

    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final AddressRepository addressRepository;
    private final ManufacturerPartRepository manufacturerPartRepository;
    private final SupplierPartRepository supplierPartRepository;
    private final SupplierPriceBreakRepository priceBreakRepository;
    private final PartService partService;
    private final CurrencyService currencyService;
    private final EntitlementService entitlementService;

    public CompanyService(
            CompanyRepository companyRepository,
            ContactRepository contactRepository,
            AddressRepository addressRepository,
            ManufacturerPartRepository manufacturerPartRepository,
            SupplierPartRepository supplierPartRepository,
            SupplierPriceBreakRepository priceBreakRepository,
            PartService partService,
            CurrencyService currencyService,
            EntitlementService entitlementService
    ) {
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.addressRepository = addressRepository;
        this.manufacturerPartRepository = manufacturerPartRepository;
        this.supplierPartRepository = supplierPartRepository;
        this.priceBreakRepository = priceBreakRepository;
        this.partService = partService;
        this.currencyService = currencyService;
        this.entitlementService = entitlementService;
    }

    // =========================================================================
    // 3.1 Companies, Contacts & Addresses
    // =========================================================================

    @Transactional(readOnly = true)
    public Page<CompanyView> searchCompanies(
            String q,
            Boolean supplier,
            Boolean manufacturer,
            Boolean customer,
            Boolean active,
            Pageable pageable
    ) {
        Specification<Company> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String pat = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pat),
                        cb.like(cb.lower(cb.coalesce(root.get("code"), "")), pat)
                ));
            }
            if (supplier != null) preds.add(cb.equal(root.get("supplier"), supplier));
            if (manufacturer != null) preds.add(cb.equal(root.get("manufacturer"), manufacturer));
            if (customer != null) preds.add(cb.equal(root.get("customer"), customer));
            if (active != null) preds.add(cb.equal(root.get("active"), active));
            return cb.and(preds.toArray(new Predicate[0]));
        };
        return companyRepository.findAll(spec, pageable).map(CompanyService::toCompanyView);
    }

    @Transactional(readOnly = true)
    public CompanyView getCompany(UUID id) {
        return toCompanyView(requireCompany(id));
    }

    @Transactional
    public CompanyView createCompany(CreateCompanyCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Company name is required");
        }
        String name = cmd.name().trim();
        if (companyRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Company '" + name + "' already exists");
        }

        String baseCurrency = currencyService.getTenantBaseCurrency();
        String currency = cmd.defaultCurrency() != null && !cmd.defaultCurrency().isBlank()
                ? cmd.defaultCurrency().trim().toUpperCase(Locale.ROOT)
                : baseCurrency;
        if (!currency.equalsIgnoreCase(baseCurrency)) {
            entitlementService.requireFeature(Feature.MULTI_CURRENCY);
        }

        Company c = new Company();
        c.setName(name);
        c.setCode(cmd.code() != null && !cmd.code().isBlank() ? cmd.code().trim() : null);
        c.setDescription(cmd.description());
        c.setWebsite(cmd.website());
        c.setEmail(cmd.email());
        c.setPhone(cmd.phone());
        c.setTaxId(cmd.taxId());
        c.setDefaultCurrency(currency);
        c.setSupplier(Boolean.TRUE.equals(cmd.supplier()));
        c.setManufacturer(Boolean.TRUE.equals(cmd.manufacturer()));
        c.setCustomer(Boolean.TRUE.equals(cmd.customer()));
        c.setInternalCompany(Boolean.TRUE.equals(cmd.internalCompany()));
        c.setActive(cmd.active() == null || cmd.active());

        return toCompanyView(companyRepository.saveAndFlush(c));
    }

    @Transactional
    public CompanyView updateCompany(UUID id, CreateCompanyCommand cmd) {
        Company c = requireCompany(id);
        if (cmd.name() != null && !cmd.name().isBlank()) {
            String newName = cmd.name().trim();
            if (!newName.equalsIgnoreCase(c.getName()) && companyRepository.existsByNameIgnoreCase(newName)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Company '" + newName + "' already exists");
            }
            c.setName(newName);
        }
        if (cmd.code() != null) c.setCode(cmd.code().isBlank() ? null : cmd.code().trim());
        if (cmd.description() != null) c.setDescription(cmd.description());
        if (cmd.website() != null) c.setWebsite(cmd.website());
        if (cmd.email() != null) c.setEmail(cmd.email());
        if (cmd.phone() != null) c.setPhone(cmd.phone());
        if (cmd.taxId() != null) c.setTaxId(cmd.taxId());
        if (cmd.defaultCurrency() != null && !cmd.defaultCurrency().isBlank()) {
            String nextCurr = cmd.defaultCurrency().trim().toUpperCase(Locale.ROOT);
            if (!nextCurr.equalsIgnoreCase(currencyService.getTenantBaseCurrency())) {
                entitlementService.requireFeature(Feature.MULTI_CURRENCY);
            }
            c.setDefaultCurrency(nextCurr);
        }
        if (cmd.supplier() != null) c.setSupplier(cmd.supplier());
        if (cmd.manufacturer() != null) c.setManufacturer(cmd.manufacturer());
        if (cmd.customer() != null) c.setCustomer(cmd.customer());
        if (cmd.internalCompany() != null) c.setInternalCompany(cmd.internalCompany());
        if (cmd.active() != null) c.setActive(cmd.active());

        return toCompanyView(companyRepository.saveAndFlush(c));
    }

    @Transactional
    public void deleteCompany(UUID id) {
        Company c = requireCompany(id);
        companyRepository.delete(c);
    }

    @Transactional(readOnly = true)
    public List<ContactView> listContacts(UUID companyId) {
        requireCompany(companyId);
        return contactRepository.findByCompanyIdOrderByPrimaryContactDescNameAsc(companyId).stream()
                .map(c -> new ContactView(
                        c.getId(), c.getCompanyId(), c.getName(), c.getRoleTitle(),
                        c.getEmail(), c.getPhone(), c.isPrimaryContact()
                ))
                .toList();
    }

    @Transactional
    public ContactView createContact(UUID companyId, CreateContactCommand cmd) {
        requireCompany(companyId);
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact name is required");
        }
        Contact c = new Contact();
        c.setCompanyId(companyId);
        c.setName(cmd.name().trim());
        c.setRoleTitle(cmd.roleTitle());
        c.setEmail(cmd.email());
        c.setPhone(cmd.phone());
        c.setPrimaryContact(Boolean.TRUE.equals(cmd.primaryContact()));
        Contact saved = contactRepository.saveAndFlush(c);
        return new ContactView(
                saved.getId(), saved.getCompanyId(), saved.getName(), saved.getRoleTitle(),
                saved.getEmail(), saved.getPhone(), saved.isPrimaryContact()
        );
    }

    @Transactional
    public void deleteContact(UUID companyId, UUID contactId) {
        requireCompany(companyId);
        Contact c = contactRepository.findById(contactId)
                .filter(ct -> ct.getCompanyId().equals(companyId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found"));
        contactRepository.delete(c);
    }

    @Transactional(readOnly = true)
    public List<AddressView> listAddresses(UUID companyId) {
        requireCompany(companyId);
        return addressRepository.findByCompanyIdOrderByLabelAsc(companyId).stream()
                .map(a -> new AddressView(
                        a.getId(), a.getCompanyId(), a.getLabel(), a.getLine1(), a.getLine2(),
                        a.getCity(), a.getStateRegion(), a.getPostalCode(), a.getCountry(),
                        a.isPrimaryShipping(), a.isPrimaryBilling()
                ))
                .toList();
    }

    @Transactional
    public AddressView createAddress(UUID companyId, CreateAddressCommand cmd) {
        requireCompany(companyId);
        if (cmd.label() == null || cmd.label().isBlank() || cmd.line1() == null || cmd.line1().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Address label and line1 are required");
        }
        Address a = new Address();
        a.setCompanyId(companyId);
        a.setLabel(cmd.label().trim());
        a.setLine1(cmd.line1().trim());
        a.setLine2(cmd.line2());
        a.setCity(cmd.city());
        a.setStateRegion(cmd.stateRegion());
        a.setPostalCode(cmd.postalCode());
        a.setCountry(cmd.country() != null && !cmd.country().isBlank() ? cmd.country().trim() : "US");
        a.setPrimaryShipping(Boolean.TRUE.equals(cmd.primaryShipping()));
        a.setPrimaryBilling(Boolean.TRUE.equals(cmd.primaryBilling()));
        Address saved = addressRepository.saveAndFlush(a);
        return new AddressView(
                saved.getId(), saved.getCompanyId(), saved.getLabel(), saved.getLine1(), saved.getLine2(),
                saved.getCity(), saved.getStateRegion(), saved.getPostalCode(), saved.getCountry(),
                saved.isPrimaryShipping(), saved.isPrimaryBilling()
        );
    }

    @Transactional
    public void deleteAddress(UUID companyId, UUID addressId) {
        requireCompany(companyId);
        Address a = addressRepository.findById(addressId)
                .filter(ad -> ad.getCompanyId().equals(companyId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));
        addressRepository.delete(a);
    }

    // =========================================================================
    // 3.2 ManufacturerPart, SupplierPart & SupplierPriceBreak
    // =========================================================================

    @Transactional(readOnly = true)
    public List<ManufacturerPartView> listManufacturerPartsForPart(UUID partId) {
        Part part = partService.requirePart(partId);
        return manufacturerPartRepository.findByPartIdOrderByMpnAsc(partId).stream()
                .map(mp -> {
                    Company mfr = companyRepository.findById(mp.getManufacturerId()).orElse(null);
                    return new ManufacturerPartView(
                            mp.getId(),
                            part.getId(),
                            part.getIpn(),
                            part.getName(),
                            mp.getManufacturerId(),
                            mfr != null ? mfr.getName() : "Unknown",
                            mp.getMpn(),
                            mp.getDescription(),
                            mp.getDatasheetUrl(),
                            mp.getCreatedAt()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ManufacturerPartView> listManufacturerPartsForCompany(UUID manufacturerId) {
        Company mfr = requireCompany(manufacturerId);
        return manufacturerPartRepository.findByManufacturerIdOrderByMpnAsc(manufacturerId).stream()
                .map(mp -> {
                    Part part = partService.requirePart(mp.getPartId());
                    return new ManufacturerPartView(
                            mp.getId(),
                            part.getId(),
                            part.getIpn(),
                            part.getName(),
                            mfr.getId(),
                            mfr.getName(),
                            mp.getMpn(),
                            mp.getDescription(),
                            mp.getDatasheetUrl(),
                            mp.getCreatedAt()
                    );
                })
                .toList();
    }

    @Transactional
    public ManufacturerPartView createManufacturerPart(CreateManufacturerPartCommand cmd) {
        if (cmd.partId() == null || cmd.manufacturerId() == null || cmd.mpn() == null || cmd.mpn().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId, manufacturerId, and mpn are required");
        }
        Part part = partService.requirePart(cmd.partId());
        Company mfr = requireCompany(cmd.manufacturerId());
        if (!mfr.isManufacturer()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "COMPANY_NOT_A_MANUFACTURER: Company '" + mfr.getName() + "' does not have the manufacturer role flag enabled"
            );
        }
        String mpn = cmd.mpn().trim();
        if (manufacturerPartRepository.existsByManufacturerIdAndMpnIgnoreCase(mfr.getId(), mpn)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Manufacturer part '" + mpn + "' already exists for " + mfr.getName());
        }

        ManufacturerPart mp = new ManufacturerPart();
        mp.setPartId(part.getId());
        mp.setManufacturerId(mfr.getId());
        mp.setMpn(mpn);
        mp.setDescription(cmd.description());
        mp.setDatasheetUrl(cmd.datasheetUrl());
        ManufacturerPart saved = manufacturerPartRepository.saveAndFlush(mp);

        return new ManufacturerPartView(
                saved.getId(),
                part.getId(),
                part.getIpn(),
                part.getName(),
                mfr.getId(),
                mfr.getName(),
                saved.getMpn(),
                saved.getDescription(),
                saved.getDatasheetUrl(),
                saved.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<SupplierPartView> listSupplierPartsForPart(UUID partId) {
        partService.requirePart(partId);
        return supplierPartRepository.findByPartIdOrderBySkuAsc(partId).stream()
                .map(this::enrichSupplierPart)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierPartView> listSupplierPartsForCompany(UUID supplierId) {
        requireCompany(supplierId);
        return supplierPartRepository.findBySupplierIdOrderBySkuAsc(supplierId).stream()
                .map(this::enrichSupplierPart)
                .toList();
    }

    @Transactional(readOnly = true)
    public SupplierPartView getSupplierPart(UUID id) {
        SupplierPart sp = requireSupplierPart(id);
        return enrichSupplierPart(sp);
    }

    @Transactional(readOnly = true)
    public Optional<SupplierPartView> findByCustomBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return Optional.empty();
        }
        return supplierPartRepository.findByCustomBarcode(barcode.trim()).map(this::enrichSupplierPart);
    }

    @Transactional
    public SupplierPartView createSupplierPart(CreateSupplierPartCommand cmd) {
        if (cmd.partId() == null || cmd.supplierId() == null || cmd.sku() == null || cmd.sku().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId, supplierId, and sku are required");
        }
        Part part = partService.requirePart(cmd.partId());
        Company supplier = requireCompany(cmd.supplierId());
        if (!supplier.isSupplier()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "COMPANY_NOT_A_SUPPLIER: Company '" + supplier.getName() + "' does not have the supplier role flag enabled"
            );
        }
        if (cmd.manufacturerPartId() != null) {
            ManufacturerPart mp = manufacturerPartRepository.findById(cmd.manufacturerPartId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Manufacturer part not found"));
            if (!mp.getPartId().equals(part.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "ManufacturerPart belongs to a different internal Part");
            }
        }

        String sku = cmd.sku().trim();
        if (supplierPartRepository.existsBySupplierIdAndSkuIgnoreCase(supplier.getId(), sku)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier SKU '" + sku + "' already exists for " + supplier.getName());
        }

        String baseCurrency = currencyService.getTenantBaseCurrency();
        String currency = (cmd.currency() != null && !cmd.currency().isBlank())
                ? cmd.currency().trim().toUpperCase(Locale.ROOT)
                : supplier.getDefaultCurrency();
        if (!currency.equalsIgnoreCase(baseCurrency)) {
            entitlementService.requireFeature(Feature.MULTI_CURRENCY);
        }

        BigDecimal packQty = cmd.packQuantity() != null ? cmd.packQuantity() : BigDecimal.ONE;
        if (packQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "packQuantity must be positive");
        }

        SupplierPart sp = new SupplierPart();
        sp.setPartId(part.getId());
        sp.setSupplierId(supplier.getId());
        sp.setManufacturerPartId(cmd.manufacturerPartId());
        sp.setSku(sku);
        sp.setDescription(cmd.description());
        sp.setPackQuantity(packQty);
        sp.setMoq(cmd.moq() != null && cmd.moq().compareTo(BigDecimal.ZERO) > 0 ? cmd.moq() : BigDecimal.ONE);
        sp.setMultiple(cmd.multiple() != null && cmd.multiple().compareTo(BigDecimal.ZERO) > 0 ? cmd.multiple() : BigDecimal.ONE);
        sp.setLeadTimeDays(cmd.leadTimeDays());
        sp.setCurrency(currency);
        sp.setCustomBarcode(cmd.customBarcode() != null && !cmd.customBarcode().isBlank() ? cmd.customBarcode().trim() : null);
        sp.setActive(true);

        SupplierPart saved = supplierPartRepository.saveAndFlush(sp);
        return enrichSupplierPart(saved);
    }

    @Transactional
    public SupplierPriceBreakView upsertPriceBreak(UUID supplierPartId, UpsertPriceBreakCommand cmd) {
        SupplierPart sp = requireSupplierPart(supplierPartId);
        if (cmd.minQuantity() == null || cmd.minQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minQuantity must be positive");
        }
        if (cmd.unitPrice() == null || cmd.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unitPrice cannot be negative");
        }

        String baseCurrency = currencyService.getTenantBaseCurrency();
        String currency = (cmd.currency() != null && !cmd.currency().isBlank())
                ? cmd.currency().trim().toUpperCase(Locale.ROOT)
                : sp.getCurrency();
        if (!currency.equalsIgnoreCase(baseCurrency)) {
            entitlementService.requireFeature(Feature.MULTI_CURRENCY);
        }

        List<SupplierPriceBreak> existingList = priceBreakRepository.findBySupplierPartIdOrderByMinQuantityAsc(supplierPartId);
        SupplierPriceBreak pb = existingList.stream()
                .filter(b -> b.getMinQuantity().compareTo(cmd.minQuantity()) == 0)
                .findFirst()
                .orElseGet(() -> {
                    SupplierPriceBreak created = new SupplierPriceBreak();
                    created.setSupplierPartId(supplierPartId);
                    created.setMinQuantity(cmd.minQuantity());
                    return created;
                });

        pb.setUnitPrice(cmd.unitPrice());
        pb.setCurrency(currency);
        SupplierPriceBreak saved = priceBreakRepository.saveAndFlush(pb);
        return toPriceBreakView(saved, sp.getPackQuantity(), baseCurrency);
    }

    @Transactional
    public void deletePriceBreak(UUID supplierPartId, UUID priceBreakId) {
        requireSupplierPart(supplierPartId);
        SupplierPriceBreak pb = priceBreakRepository.findById(priceBreakId)
                .filter(b -> b.getSupplierPartId().equals(supplierPartId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Price break not found"));
        priceBreakRepository.delete(pb);
    }

    /**
     * Resolves the best applicable SupplierPriceBreak for an ordered pack quantity.
     */
    @Transactional(readOnly = true)
    public Optional<SupplierPriceBreakView> resolveBestPriceBreak(UUID supplierPartId, BigDecimal orderQuantityPacks) {
        SupplierPart sp = requireSupplierPart(supplierPartId);
        String baseCurrency = currencyService.getTenantBaseCurrency();
        List<SupplierPriceBreak> breaks = priceBreakRepository.findBySupplierPartIdOrderByMinQuantityAsc(supplierPartId);
        if (breaks.isEmpty()) {
            return Optional.empty();
        }
        SupplierPriceBreak best = breaks.getFirst();
        for (SupplierPriceBreak b : breaks) {
            if (b.getMinQuantity().compareTo(orderQuantityPacks) <= 0) {
                best = b;
            }
        }
        return Optional.of(toPriceBreakView(best, sp.getPackQuantity(), baseCurrency));
    }

    // =========================================================================
    // 3.4 Supplier Part CSV / XLSX Importer
    // =========================================================================

    @Transactional
    public boolean commitImportedSupplierPartRow(Map<String, String> mappedRow) {
        String ipn = mappedRow.getOrDefault("ipn", "").trim();
        String supplierName = mappedRow.getOrDefault("supplierName", "").trim();
        String sku = mappedRow.getOrDefault("sku", "").trim();
        if (ipn.isEmpty() || supplierName.isEmpty() || sku.isEmpty()) {
            return false;
        }
        Part part = partService.searchPartsByKeyword(ipn, 10).stream()
                .filter(p -> p.getIpn().equalsIgnoreCase(ipn))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Part not found for IPN: " + ipn));

        Company supplier = companyRepository.findByNameIgnoreCase(supplierName)
                .orElseGet(() -> {
                    Company c = new Company();
                    c.setName(supplierName);
                    c.setSupplier(true);
                    c.setDefaultCurrency(currencyService.getTenantBaseCurrency());
                    return companyRepository.saveAndFlush(c);
                });

        BigDecimal parsedPackQty = BigDecimal.ONE;
        String packStr = mappedRow.getOrDefault("packQuantity", "1").trim();
        if (!packStr.isEmpty()) {
            try {
                parsedPackQty = new BigDecimal(packStr);
            } catch (NumberFormatException ignored) {
            }
        }
        final BigDecimal packQty = parsedPackQty;
        String rawCurr = mappedRow.getOrDefault("currency", supplier.getDefaultCurrency()).trim();
        final String currency = rawCurr.isEmpty() ? supplier.getDefaultCurrency() : rawCurr;

        SupplierPartView spView = supplierPartRepository.findBySupplierIdAndSkuIgnoreCase(supplier.getId(), sku)
                .map(this::enrichSupplierPart)
                .orElseGet(() -> createSupplierPart(new CreateSupplierPartCommand(
                        part.getId(), supplier.getId(), null, sku,
                        mappedRow.getOrDefault("description", "").trim(),
                        packQty, BigDecimal.ONE, BigDecimal.ONE, null, currency, null
                )));

        String priceStr = mappedRow.getOrDefault("unitPrice", "").trim();
        if (!priceStr.isEmpty()) {
            try {
                BigDecimal unitPrice = new BigDecimal(priceStr);
                upsertPriceBreak(spView.id(), new UpsertPriceBreakCommand(BigDecimal.ONE, unitPrice, currency));
            } catch (NumberFormatException ignored) {
            }
        }
        return true;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @Transactional(readOnly = true)
    public Company requireCompany(UUID id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found: " + id));
    }

    @Transactional(readOnly = true)
    public SupplierPart requireSupplierPart(UUID id) {
        return supplierPartRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier part not found: " + id));
    }

    private SupplierPartView enrichSupplierPart(SupplierPart sp) {
        Part part = partService.requirePart(sp.getPartId());
        Company supplier = companyRepository.findById(sp.getSupplierId()).orElse(null);
        ManufacturerPart mp = sp.getManufacturerPartId() != null
                ? manufacturerPartRepository.findById(sp.getManufacturerPartId()).orElse(null)
                : null;
        String baseCurrency = currencyService.getTenantBaseCurrency();
        List<SupplierPriceBreakView> breaks = priceBreakRepository.findBySupplierPartIdOrderByMinQuantityAsc(sp.getId()).stream()
                .map(pb -> toPriceBreakView(pb, sp.getPackQuantity(), baseCurrency))
                .toList();

        return new SupplierPartView(
                sp.getId(),
                part.getId(),
                part.getIpn(),
                part.getName(),
                part.getUnit(),
                sp.getSupplierId(),
                supplier != null ? supplier.getName() : "Unknown",
                sp.getManufacturerPartId(),
                mp != null ? mp.getMpn() : null,
                sp.getSku(),
                sp.getDescription(),
                sp.getPackQuantity(),
                sp.getMoq(),
                sp.getMultiple(),
                sp.getLeadTimeDays(),
                sp.getCurrency(),
                sp.getCustomBarcode(),
                sp.isActive(),
                breaks,
                sp.getCreatedAt()
        );
    }

    private SupplierPriceBreakView toPriceBreakView(SupplierPriceBreak pb, BigDecimal packQuantity, String baseCurrency) {
        BigDecimal convertedPack = currencyService.convert(pb.getUnitPrice(), pb.getCurrency(), baseCurrency);
        BigDecimal safePack = (packQuantity != null && packQuantity.compareTo(BigDecimal.ZERO) > 0) ? packQuantity : BigDecimal.ONE;
        BigDecimal convertedInternalUnit = convertedPack.divide(safePack, 4, RoundingMode.HALF_UP);
        return new SupplierPriceBreakView(
                pb.getId(),
                pb.getSupplierPartId(),
                pb.getMinQuantity(),
                pb.getUnitPrice(),
                pb.getCurrency(),
                baseCurrency,
                convertedPack,
                convertedInternalUnit
        );
    }

    private static CompanyView toCompanyView(Company c) {
        return new CompanyView(
                c.getId(),
                c.getName(),
                c.getCode(),
                c.getDescription(),
                c.getWebsite(),
                c.getEmail(),
                c.getPhone(),
                c.getTaxId(),
                c.getDefaultCurrency(),
                c.isSupplier(),
                c.isManufacturer(),
                c.isCustomer(),
                c.isInternalCompany(),
                c.isActive(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
