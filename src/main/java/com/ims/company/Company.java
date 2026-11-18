package com.ims.company;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * External or internal business partner (Supplier, Manufacturer, Customer, Internal).
 */
@Entity
@Table(name = "companies")
@Audited
public class Company extends VersionedTenantEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "website", length = 300)
    private String website;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 80)
    private String phone;

    @Column(name = "tax_id", length = 100)
    private String taxId;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency = "USD";

    @Column(name = "supplier", nullable = false)
    private boolean supplier = false;

    @Column(name = "manufacturer", nullable = false)
    private boolean manufacturer = false;

    @Column(name = "customer", nullable = false)
    private boolean customer = false;

    @Column(name = "internal_company", nullable = false)
    private boolean internalCompany = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public boolean isSupplier() {
        return supplier;
    }

    public void setSupplier(boolean supplier) {
        this.supplier = supplier;
    }

    public boolean isManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(boolean manufacturer) {
        this.manufacturer = manufacturer;
    }

    public boolean isCustomer() {
        return customer;
    }

    public void setCustomer(boolean customer) {
        this.customer = customer;
    }

    public boolean isInternalCompany() {
        return internalCompany;
    }

    public void setInternalCompany(boolean internalCompany) {
        this.internalCompany = internalCompany;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
