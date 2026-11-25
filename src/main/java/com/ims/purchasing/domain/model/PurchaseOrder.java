package com.ims.purchasing.domain.model;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound Purchase Order with state machine:
 * PENDING -> PLACED -> IN_PROGRESS -> COMPLETE (or ON_HOLD / CANCELLED).
 */
@Entity
@Table(name = "purchase_orders")
@Audited
public class PurchaseOrder extends VersionedTenantEntity {

    public enum Status {
        PENDING,
        PLACED,
        IN_PROGRESS,
        COMPLETE,
        ON_HOLD,
        CANCELLED
    }

    @Column(name = "reference", nullable = false, length = 64)
    private String reference;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "supplier_reference", length = 120)
    private String supplierReference;

    @Column(name = "status", nullable = false, length = 32)
    private String status = Status.PENDING.name();

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "destination_location_id")
    private UUID destinationLocationId;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "complete_date")
    private LocalDate completeDate;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(UUID supplierId) {
        this.supplierId = supplierId;
    }

    public String getSupplierReference() {
        return supplierReference;
    }

    public void setSupplierReference(String supplierReference) {
        this.supplierReference = supplierReference;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public LocalDate getCompleteDate() {
        return completeDate;
    }

    public void setCompleteDate(LocalDate completeDate) {
        this.completeDate = completeDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
