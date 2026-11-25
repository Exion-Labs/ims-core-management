package com.ims.catalog.infrastructure.adapter;

import com.ims.catalog.spi.CatalogReadPort;
import com.ims.integration.spi.ReportDataProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class PartReportDataProvider implements ReportDataProvider {

    private final CatalogReadPort catalogReadPort;

    public PartReportDataProvider(CatalogReadPort catalogReadPort) {
        this.catalogReadPort = catalogReadPort;
    }

    @Override
    public String entityType() {
        return "PART";
    }

    @Override
    public Map<String, Object> buildModel(UUID entityId) {
        var p = catalogReadPort.requirePartSummary(entityId);
        Map<String, Object> model = new HashMap<>();
        model.put("id", p.id().toString());
        model.put("ipn", p.ipn() != null ? p.ipn() : "");
        model.put("name", p.name());
        model.put("description", p.description() != null ? p.description() : "");
        model.put("unit", p.unit() != null ? p.unit() : "");
        return model;
    }
}
