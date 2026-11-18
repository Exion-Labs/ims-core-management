package com.ims.part;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "bom_items")
@Audited
public class BomItem extends VersionedTenantEntity {

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "sub_part_id", nullable = false)
    private UUID subPartId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "reference", length = 250)
    private String reference;

    @Column(name = "optional", nullable = false)
    private boolean optional = false;

    @Column(name = "consumable", nullable = false)
    private boolean consumable = false;

    @Column(name = "allow_variants", nullable = false)
    private boolean allowVariants = true;

    @Column(name = "inherited", nullable = false)
    private boolean inherited = false;

    @Column(name = "notes", length = 500)
    private String notes;

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public UUID getSubPartId() {
        return subPartId;
    }

    public void setSubPartId(UUID subPartId) {
        this.subPartId = subPartId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public boolean isOptional() {
        return optional;
    }

    public void setOptional(boolean optional) {
        this.optional = optional;
    }

    public boolean isConsumable() {
        return consumable;
    }

    public void setConsumable(boolean consumable) {
        this.consumable = consumable;
    }

    public boolean isAllowVariants() {
        return allowVariants;
    }

    public void setAllowVariants(boolean allowVariants) {
        this.allowVariants = allowVariants;
    }

    public boolean isInherited() {
        return inherited;
    }

    public void setInherited(boolean inherited) {
        this.inherited = inherited;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
