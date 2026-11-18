package com.ims.common.entitlement;

import java.util.List;

/**
 * Code-first Feature Catalog for tenant entitlements.
 */
public enum Feature {

    // Core (Always On)
    PARTS(FeatureType.BOOLEAN, true, "Core Part Catalog & Categories", 1, List.of()),
    STOCK(FeatureType.BOOLEAN, true, "Core Warehouse Locations & Stock Engine", 2, List.of()),
    COMPANIES(FeatureType.BOOLEAN, true, "Core Companies & Supplier Catalog", 3, List.of()),

    // Boolean Feature Flags
    API_ACCESS(FeatureType.BOOLEAN, false, "REST API tokens & programmatic access", 0, List.of()),
    IMPORT_EXPORT(FeatureType.BOOLEAN, false, "CSV & XLSX bulk importers and exports", 0, List.of()),
    SERIAL_TRACKING(FeatureType.BOOLEAN, false, "Trackable serialized parts & genealogy", 2, List.of()),
    QUALITY_TESTS(FeatureType.BOOLEAN, false, "Inspection test templates & pass/fail results", 2, List.of()),
    BARCODES(FeatureType.BOOLEAN, false, "QR / DataMatrix / Code128 generation & scan workflows", 2, List.of()),
    MULTI_CURRENCY(FeatureType.BOOLEAN, false, "Foreign currencies & exchange rate conversions", 3, List.of()),
    PURCHASING(FeatureType.BOOLEAN, false, "Purchase orders & inbound receiving", 4, List.of()),
    MANUFACTURING(FeatureType.BOOLEAN, false, "Multi-level BOMs & production build orders", 5, List.of()),
    SALES(FeatureType.BOOLEAN, false, "Customer sales orders & shipments", 6, List.of()),
    RETURNS(FeatureType.BOOLEAN, false, "RMA return orders & quarantine disposition", 6, List.of(SALES)),
    TRANSFERS(FeatureType.BOOLEAN, false, "Two-stage warehouse-to-warehouse transfer orders", 6, List.of()),
    PRICING_ROLLUP(FeatureType.BOOLEAN, false, "Automated multi-level BOM cost rollup", 7, List.of(MANUFACTURING)),
    CUSTOM_REPORTS(FeatureType.BOOLEAN, false, "Tenant-customizable PDF document & label templates", 7, List.of()),
    WEBHOOKS(FeatureType.BOOLEAN, false, "HMAC-signed outbound webhooks", 7, List.of(API_ACCESS)),
    REALTIME(FeatureType.BOOLEAN, false, "Live table updates via Server-Sent Events (SSE)", 7, List.of()),

    // Quota / Limit Features
    MAX_USERS(FeatureType.LIMIT, false, "Maximum active or invited users", 0, List.of()),
    MAX_PARTS(FeatureType.LIMIT, false, "Maximum parts in catalog", 1, List.of()),
    MAX_LOCATIONS(FeatureType.LIMIT, false, "Maximum stock locations", 2, List.of()),
    MAX_WEBHOOKS(FeatureType.LIMIT, false, "Maximum active webhook subscriptions", 7, List.of()),
    STORAGE_MB(FeatureType.LIMIT, false, "Maximum attachment & report storage in MB", 0, List.of()),
    API_RATE_PER_MIN(FeatureType.LIMIT, false, "API token requests per minute", 0, List.of());

    public enum FeatureType {
        BOOLEAN,
        LIMIT
    }

    private final FeatureType type;
    private final boolean coreAlwaysOn;
    private final String description;
    private final int phase;
    private final List<Feature> dependencies;

    Feature(FeatureType type, boolean coreAlwaysOn, String description, int phase, List<Feature> dependencies) {
        this.type = type;
        this.coreAlwaysOn = coreAlwaysOn;
        this.description = description;
        this.phase = phase;
        this.dependencies = dependencies;
    }

    public FeatureType getType() {
        return type;
    }

    public boolean isCoreAlwaysOn() {
        return coreAlwaysOn;
    }

    public String getDescription() {
        return description;
    }

    public int getPhase() {
        return phase;
    }

    public List<Feature> getDependencies() {
        return dependencies;
    }
}
