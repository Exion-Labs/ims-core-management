package com.ims.manufacturing.domain.model;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "build_orders")
@Audited
public class BuildOrder extends VersionedTenantEntity {

    public enum Status {
        PENDING,
        PRODUCTION,
        COMPLETED,
        ON_HOLD,
        CANCELLED
    }

    @Column(name = "reference", nullable = false, length = 64)
    private String reference;

    @Column(name = "title", length = 250)
    private String title;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "completed", nullable = false, precision = 19, scale = 4)
    private BigDecimal completed = BigDecimal.ZERO;

    @Column(name = "parent_build_id")
    private UUID parentBuildId;

    @Column(name = "sales_order_id")
    private UUID salesOrderId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = Status.PENDING.name();

    @Column(name = "source_location_id")
    private UUID sourceLocationId;

    @Column(name = "destination_location_id")
    private UUID destinationLocationId;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "completion_date")
    private LocalDate completionDate;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getCompleted() {
        return completed;
    }

    public void setCompleted(BigDecimal completed) {
        this.completed = completed;
    }

    public UUID getParentBuildId() {
        return parentBuildId;
    }

    public void setParentBuildId(UUID parentBuildId) {
        this.parentBuildId = parentBuildId;
    }

    public UUID getSalesOrderId() {
        return salesOrderId;
    }

    public void setSalesOrderId(UUID salesOrderId) {
        this.salesOrderId = salesOrderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public void setSourceLocationId(UUID sourceLocationId) {
        this.sourceLocationId = sourceLocationId;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public LocalDate getCompletionDate() {
        return completionDate;
    }

    public void setCompletionDate(LocalDate completionDate) {
        this.completionDate = completionDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
