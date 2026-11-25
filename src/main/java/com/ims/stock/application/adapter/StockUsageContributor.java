package com.ims.stock.application.adapter;

import com.ims.stock.application.*;

import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.TenantUsageContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StockUsageContributor implements TenantUsageContributor {

    private final StockLocationService stockLocationService;

    public StockUsageContributor(StockLocationService stockLocationService) {
        this.stockLocationService = stockLocationService;
    }

    @Override
    public Map<Feature, Long> collectUsage() {
        return Map.of(Feature.MAX_LOCATIONS, stockLocationService.countLocations());
    }
}
