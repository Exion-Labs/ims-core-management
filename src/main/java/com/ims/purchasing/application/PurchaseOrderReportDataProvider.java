package com.ims.purchasing.application;

import com.ims.integration.spi.ReportDataProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class PurchaseOrderReportDataProvider implements ReportDataProvider {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderReportDataProvider(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @Override
    public String entityType() {
        return "PURCHASE_ORDER";
    }

    @Override
    public Map<String, Object> buildModel(UUID entityId) {
        var po = purchaseOrderService.getPurchaseOrder(entityId);
        Map<String, Object> model = new HashMap<>();
        model.put("id", po.id().toString());
        model.put("reference", po.reference());
        model.put("supplierName", po.supplierName());
        model.put("status", po.status());
        model.put("currency", po.currency());
        return model;
    }
}
