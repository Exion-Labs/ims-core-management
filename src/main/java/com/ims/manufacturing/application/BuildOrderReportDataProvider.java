package com.ims.manufacturing.application;

import com.ims.integration.spi.ReportDataProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class BuildOrderReportDataProvider implements ReportDataProvider {

    private final BuildService buildService;

    public BuildOrderReportDataProvider(BuildService buildService) {
        this.buildService = buildService;
    }

    @Override
    public String entityType() {
        return "BUILD_ORDER";
    }

    @Override
    public Map<String, Object> buildModel(UUID entityId) {
        var bo = buildService.getBuildOrder(entityId);
        Map<String, Object> model = new HashMap<>();
        model.put("id", bo.id().toString());
        model.put("reference", bo.reference());
        model.put("title", bo.title());
        model.put("partName", bo.partName());
        model.put("quantity", bo.quantity().toPlainString());
        model.put("status", bo.status());
        return model;
    }
}
