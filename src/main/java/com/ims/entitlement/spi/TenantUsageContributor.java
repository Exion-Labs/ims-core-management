package com.ims.entitlement.spi;

import java.util.Map;

/**
 * SPI implemented by domain modules to report current tenant quota usage (e.g., MAX_PARTS, MAX_LOCATIONS).
 */
public interface TenantUsageContributor {

    Map<Feature, Long> collectUsage();
}
