package com.ims.catalog.infrastructure.adapter;

import com.ims.catalog.application.*;
import com.ims.catalog.domain.model.*;

import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.TenantUsageContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PartUsageContributor implements TenantUsageContributor {

    private final PartService partService;

    public PartUsageContributor(PartService partService) {
        this.partService = partService;
    }

    @Override
    public Map<Feature, Long> collectUsage() {
        return Map.of(Feature.MAX_PARTS, partService.countParts());
    }
}
