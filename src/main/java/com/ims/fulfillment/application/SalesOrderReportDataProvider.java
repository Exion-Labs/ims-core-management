package com.ims.fulfillment.application;

import com.ims.integration.spi.ReportDataProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class SalesOrderReportDataProvider implements ReportDataProvider {

    private final SalesOrderService salesOrderService;

    public SalesOrderReportDataProvider(SalesOrderService salesOrderService) {
        this.salesOrderService = salesOrderService;
    }

    @Override
    public String entityType() {
        return "SALES_ORDER";
    }

    @Override
    public Map<String, Object> buildModel(UUID entityId) {
        var so = salesOrderService.getSalesOrder(entityId);
        Map<String, Object> model = new HashMap<>();
        model.put("id", so.id().toString());
        model.put("reference", so.reference());
        model.put("customerName", so.customerName());
        model.put("status", so.status());
        model.put("currency", so.currency());
        return model;
    }
}
