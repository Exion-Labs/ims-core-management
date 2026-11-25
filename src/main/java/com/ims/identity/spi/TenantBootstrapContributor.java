package com.ims.identity.spi;

import java.util.UUID;

/**
 * SPI invoked inside TenantContext.runAs(newTenantId) during tenant provisioning to seed module defaults.
 */
public interface TenantBootstrapContributor {

    void seedDefaults(UUID tenantId, String tenantName, String baseCurrency, String timezone);
}
