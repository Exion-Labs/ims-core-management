package com.ims.fulfillment.domain.model;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sales_order_shipments")
public class SalesOrderShipment extends BaseTenantEntity {

    @Column(name = "sales_order_id", nullable = false)
    private UUID salesOrderId;

    @Column(nullable = false, length = 64)
    private String reference;

    @Column(name = "tracking_number", length = 128)
    private String trackingNumber;

    @Column(name = "invoice_number", length = 128)
    private String invoiceNumber;

    @Column(length = 128)
    private String carrier;

    @Column(name = "shipment_date")
    private LocalDate shipmentDate;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(columnDefinition = "text")
    private String notes;

    public UUID getSalesOrderId() { return salesOrderId; }
    public void setSalesOrderId(UUID salesOrderId) { this.salesOrderId = salesOrderId; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }
    public LocalDate getShipmentDate() { return shipmentDate; }
    public void setShipmentDate(LocalDate shipmentDate) { this.shipmentDate = shipmentDate; }
    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
