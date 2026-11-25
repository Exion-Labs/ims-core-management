package com.ims.partner.api;

import com.ims.partner.application.*;
import com.ims.partner.domain.model.ExchangeRate;

import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiresFeature(Feature.COMPANIES)
public class CompanyController {

    public record UpsertRatePayload(String targetCurrency, BigDecimal rate) {}

    private final CompanyService companyService;
    private final CurrencyService currencyService;

    public CompanyController(CompanyService companyService, CurrencyService currencyService) {
        this.companyService = companyService;
        this.currencyService = currencyService;
    }

    // =========================================================================
    // 3.1 Companies, Contacts & Addresses
    // =========================================================================

    @GetMapping("/companies")
    @PreAuthorize("hasAuthority('COMPANY:VIEW')")
    public Page<CompanyService.CompanyView> searchCompanies(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean supplier,
            @RequestParam(required = false) Boolean manufacturer,
            @RequestParam(required = false) Boolean customer,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return companyService.searchCompanies(
                q, supplier, manufacturer, customer, active,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.ASC, "name"))
        );
    }

    @GetMapping("/companies/{id}")
    @PreAuthorize("hasAuthority('COMPANY:VIEW')")
    public CompanyService.CompanyView getCompany(@PathVariable UUID id) {
        return companyService.getCompany(id);
    }

    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPANY:CREATE')")
    public CompanyService.CompanyView createCompany(
            @Valid @RequestBody CompanyService.CreateCompanyCommand command
    ) {
        return companyService.createCompany(command);
    }

    @PutMapping("/companies/{id}")
    @PreAuthorize("hasAuthority('COMPANY:UPDATE')")
    public CompanyService.CompanyView updateCompany(
            @PathVariable UUID id,
            @Valid @RequestBody CompanyService.CreateCompanyCommand command
    ) {
        return companyService.updateCompany(id, command);
    }

    @DeleteMapping("/companies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('COMPANY:DELETE')")
    public void deleteCompany(@PathVariable UUID id) {
        companyService.deleteCompany(id);
    }

    @GetMapping("/companies/{id}/contacts")
    @PreAuthorize("hasAuthority('COMPANY:VIEW')")
    public List<CompanyService.ContactView> listContacts(@PathVariable UUID id) {
        return companyService.listContacts(id);
    }

    @PostMapping("/companies/{id}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPANY:UPDATE')")
    public CompanyService.ContactView createContact(
            @PathVariable UUID id,
            @Valid @RequestBody CompanyService.CreateContactCommand command
    ) {
        return companyService.createContact(id, command);
    }

    @DeleteMapping("/companies/{id}/contacts/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('COMPANY:UPDATE')")
    public void deleteContact(@PathVariable UUID id, @PathVariable UUID contactId) {
        companyService.deleteContact(id, contactId);
    }

    @GetMapping("/companies/{id}/addresses")
    @PreAuthorize("hasAuthority('COMPANY:VIEW')")
    public List<CompanyService.AddressView> listAddresses(@PathVariable UUID id) {
        return companyService.listAddresses(id);
    }

    @PostMapping("/companies/{id}/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPANY:UPDATE')")
    public CompanyService.AddressView createAddress(
            @PathVariable UUID id,
            @Valid @RequestBody CompanyService.CreateAddressCommand command
    ) {
        return companyService.createAddress(id, command);
    }

    @DeleteMapping("/companies/{id}/addresses/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('COMPANY:UPDATE')")
    public void deleteAddress(@PathVariable UUID id, @PathVariable UUID addressId) {
        companyService.deleteAddress(id, addressId);
    }

    // =========================================================================
    // 3.2 Manufacturer Parts, Supplier Parts & Tiered Price Breaks
    // =========================================================================

    @GetMapping("/parts/{partId}/manufacturer-parts")
    @PreAuthorize("hasAuthority('COMPANY:VIEW')")
    public List<CompanyService.ManufacturerPartView> listManufacturerPartsForPart(@PathVariable UUID partId) {
        return companyService.listManufacturerPartsForPart(partId);
    }

    @GetMapping("/companies/{companyId}/manufacturer-parts")
    @PreAuthorize("hasAuthority('COMPANY:VIEW')")
    public List<CompanyService.ManufacturerPartView> listManufacturerPartsForCompany(@PathVariable UUID companyId) {
        return companyService.listManufacturerPartsForCompany(companyId);
    }

    @PostMapping("/manufacturer-parts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPANY:CREATE')")
    public CompanyService.ManufacturerPartView createManufacturerPart(
            @Valid @RequestBody CompanyService.CreateManufacturerPartCommand command
    ) {
        return companyService.createManufacturerPart(command);
    }

    @GetMapping("/parts/{partId}/supplier-parts")
    @PreAuthorize("hasAuthority('COMPANY:VIEW')")
    public List<CompanyService.SupplierPartView> listSupplierPartsForPart(@PathVariable UUID partId) {
        return companyService.listSupplierPartsForPart(partId);
    }

    @GetMapping("/companies/{companyId}/supplier-parts")
    @PreAuthorize("hasAuthority('COMPANY:VIEW')")
    public List<CompanyService.SupplierPartView> listSupplierPartsForCompany(@PathVariable UUID companyId) {
        return companyService.listSupplierPartsForCompany(companyId);
    }

    @PostMapping("/supplier-parts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPANY:CREATE')")
    public CompanyService.SupplierPartView createSupplierPart(
            @Valid @RequestBody CompanyService.CreateSupplierPartCommand command
    ) {
        return companyService.createSupplierPart(command);
    }

    @PostMapping("/supplier-parts/{supplierPartId}/price-breaks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMPANY:UPDATE')")
    public CompanyService.SupplierPriceBreakView upsertPriceBreak(
            @PathVariable UUID supplierPartId,
            @Valid @RequestBody CompanyService.UpsertPriceBreakCommand command
    ) {
        return companyService.upsertPriceBreak(supplierPartId, command);
    }

    @DeleteMapping("/supplier-parts/{supplierPartId}/price-breaks/{priceBreakId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('COMPANY:UPDATE')")
    public void deletePriceBreak(@PathVariable UUID supplierPartId, @PathVariable UUID priceBreakId) {
        companyService.deletePriceBreak(supplierPartId, priceBreakId);
    }

    // =========================================================================
    // 3.3 Currency & Exchange Rates
    // =========================================================================

    @GetMapping("/currencies/rates")
    @PreAuthorize("hasAuthority('COMPANY:VIEW')")
    public List<ExchangeRate> listExchangeRates() {
        return currencyService.listExchangeRates();
    }

    @PostMapping("/currencies/rates")
    @PreAuthorize("hasAuthority('COMPANY:UPDATE')")
    @RequiresFeature(Feature.MULTI_CURRENCY)
    public ExchangeRate upsertExchangeRate(@Valid @RequestBody UpsertRatePayload payload) {
        return currencyService.upsertExchangeRate(payload.targetCurrency(), payload.rate());
    }
}
