package com.ims.entitlement.spi;

public class FeatureNotEnabledException extends RuntimeException {

    private final Feature feature;

    public FeatureNotEnabledException(Feature feature) {
        super("Feature '" + feature.name() + "' is not enabled for this tenant.");
        this.feature = feature;
    }

    public Feature getFeature() {
        return feature;
    }
}
