package com.ims.part;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Core Part master record supporting variants (variantOfId), revisions (revisionOfId), and engineering flags.
 */
@Entity
@Table(name = "parts")
@Audited
public class Part extends VersionedTenantEntity {

    @Column(name = "ipn", nullable = false, length = 100)
    private String ipn;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "revision", nullable = false, length = 32)
    private String revision = "A";

    @Column(name = "revision_of_id")
    private UUID revisionOfId;

    @Column(name = "variant_of_id")
    private UUID variantOfId;

    @Column(name = "unit", nullable = false, length = 32)
    private String unit = "pcs";

    @Column(name = "minimum_stock", nullable = false, precision = 19, scale = 4)
    private BigDecimal minimumStock = BigDecimal.ZERO;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "locked", nullable = false)
    private boolean locked = false;

    @Column(name = "assembly", nullable = false)
    private boolean assembly = false;

    @Column(name = "component", nullable = false)
    private boolean component = true;

    @Column(name = "trackable", nullable = false)
    private boolean trackable = false;

    @Column(name = "purchaseable", nullable = false)
    private boolean purchaseable = true;

    @Column(name = "salable", nullable = false)
    private boolean salable = false;

    @Column(name = "virtual", nullable = false)
    private boolean virtual = false;

    public String getIpn() {
        return ipn;
    }

    public void setIpn(String ipn) {
        this.ipn = ipn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public UUID getRevisionOfId() {
        return revisionOfId;
    }

    public void setRevisionOfId(UUID revisionOfId) {
        this.revisionOfId = revisionOfId;
    }

    public UUID getVariantOfId() {
        return variantOfId;
    }

    public void setVariantOfId(UUID variantOfId) {
        this.variantOfId = variantOfId;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getMinimumStock() {
        return minimumStock;
    }

    public void setMinimumStock(BigDecimal minimumStock) {
        this.minimumStock = minimumStock;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public boolean isAssembly() {
        return assembly;
    }

    public void setAssembly(boolean assembly) {
        this.assembly = assembly;
    }

    public boolean isComponent() {
        return component;
    }

    public void setComponent(boolean component) {
        this.component = component;
    }

    public boolean isTrackable() {
        return trackable;
    }

    public void setTrackable(boolean trackable) {
        this.trackable = trackable;
    }

    public boolean isPurchaseable() {
        return purchaseable;
    }

    public void setPurchaseable(boolean purchaseable) {
        this.purchaseable = purchaseable;
    }

    public boolean isSalable() {
        return salable;
    }

    public void setSalable(boolean salable) {
        this.salable = salable;
    }

    public boolean isVirtual() {
        return virtual;
    }

    public void setVirtual(boolean virtual) {
        this.virtual = virtual;
    }
}
