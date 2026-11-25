package com.ims.partner.domain.model;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "supplier_parts")
@Audited
public class SupplierPart extends VersionedTenantEntity {

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "manufacturer_part_id")
    private UUID manufacturerPartId;

    @Column(name = "sku", nullable = false, length = 120)
    private String sku;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "pack_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal packQuantity = BigDecimal.ONE;

    @Column(name = "moq", nullable = false, precision = 19, scale = 4)
    private BigDecimal moq = BigDecimal.ONE;

    @Column(name = "multiple", nullable = false, precision = 19, scale = 4)
    private BigDecimal multiple = BigDecimal.ONE;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "custom_barcode", length = 128)
    private String customBarcode;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(UUID supplierId) {
        this.supplierId = supplierId;
    }

    public UUID getManufacturerPartId() {
        return manufacturerPartId;
    }

    public void setManufacturerPartId(UUID manufacturerPartId) {
        this.manufacturerPartId = manufacturerPartId;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPackQuantity() {
        return packQuantity;
    }

    public void setPackQuantity(BigDecimal packQuantity) {
        this.packQuantity = packQuantity;
    }

    public BigDecimal getMoq() {
        return moq;
    }

    public void setMoq(BigDecimal moq) {
        this.moq = moq;
    }

    public BigDecimal getMultiple() {
        return multiple;
    }

    public void setMultiple(BigDecimal multiple) {
        this.multiple = multiple;
    }

    public Integer getLeadTimeDays() {
        return leadTimeDays;
    }

    public void setLeadTimeDays(Integer leadTimeDays) {
        this.leadTimeDays = leadTimeDays;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getCustomBarcode() {
        return customBarcode;
    }

    public void setCustomBarcode(String customBarcode) {
        this.customBarcode = customBarcode;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
