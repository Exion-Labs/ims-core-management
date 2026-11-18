package com.ims.order.fulfillment;

import com.ims.common.VersionedTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "return_orders")
public class ReturnOrder extends VersionedTenantEntity {

    @Column(nullable = false, length = 64)
    private String reference;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "sales_order_id")
    private UUID salesOrderId;

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
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public UUID getSalesOrderId() { return salesOrderId; }
    public void setSalesOrderId(UUID salesOrderId) { this.salesOrderId = salesOrderId; }
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
