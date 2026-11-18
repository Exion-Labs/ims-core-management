package com.ims.order.fulfillment;

import com.ims.common.VersionedTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sales_orders")
public class SalesOrder extends VersionedTenantEntity {

    @Column(nullable = false, length = 64)
    private String reference;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_reference", length = 128)
    private String customerReference;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "shipment_date")
    private LocalDate shipmentDate;

    @Column(columnDefinition = "text")
    private String notes;

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getCustomerReference() { return customerReference; }
    public void setCustomerReference(String customerReference) { this.customerReference = customerReference; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDate getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDate orderDate) { this.orderDate = orderDate; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
    public LocalDate getShipmentDate() { return shipmentDate; }
    public void setShipmentDate(LocalDate shipmentDate) { this.shipmentDate = shipmentDate; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
