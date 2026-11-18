package com.ims.company;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "addresses")
@Audited
public class Address extends VersionedTenantEntity {

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "line1", nullable = false, length = 250)
    private String line1;

    @Column(name = "line2", length = 250)
    private String line2;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "state_region", length = 120)
    private String stateRegion;

    @Column(name = "postal_code", length = 40)
    private String postalCode;

    @Column(name = "country", nullable = false, length = 80)
    private String country = "US";

    @Column(name = "primary_shipping", nullable = false)
    private boolean primaryShipping = false;

    @Column(name = "primary_billing", nullable = false)
    private boolean primaryBilling = false;

    public UUID getCompanyId() {
        return companyId;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getLine1() {
        return line1;
    }

    public void setLine1(String line1) {
        this.line1 = line1;
    }

    public String getLine2() {
        return line2;
    }

    public void setLine2(String line2) {
        this.line2 = line2;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getStateRegion() {
        return stateRegion;
    }

    public void setStateRegion(String stateRegion) {
        this.stateRegion = stateRegion;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public boolean isPrimaryShipping() {
        return primaryShipping;
    }

    public void setPrimaryShipping(boolean primaryShipping) {
        this.primaryShipping = primaryShipping;
    }

    public boolean isPrimaryBilling() {
        return primaryBilling;
    }

    public void setPrimaryBilling(boolean primaryBilling) {
        this.primaryBilling = primaryBilling;
    }
}
