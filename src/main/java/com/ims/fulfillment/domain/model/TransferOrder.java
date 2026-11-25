package com.ims.fulfillment.domain.model;

import com.ims.common.VersionedTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "transfer_orders")
public class TransferOrder extends VersionedTenantEntity {

    @Column(nullable = false, length = 64)
    private String reference;

    @Column(name = "source_location_id", nullable = false)
    private UUID sourceLocationId;

    @Column(name = "destination_location_id", nullable = false)
    private UUID destinationLocationId;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(length = 500)
    private String description;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "completion_date")
    private LocalDate completionDate;

    @Column(columnDefinition = "text")
    private String notes;

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public UUID getSourceLocationId() { return sourceLocationId; }
    public void setSourceLocationId(UUID sourceLocationId) { this.sourceLocationId = sourceLocationId; }
    public UUID getDestinationLocationId() { return destinationLocationId; }
    public void setDestinationLocationId(UUID destinationLocationId) { this.destinationLocationId = destinationLocationId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
    public LocalDate getCompletionDate() { return completionDate; }
    public void setCompletionDate(LocalDate completionDate) { this.completionDate = completionDate; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
