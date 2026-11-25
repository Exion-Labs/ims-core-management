package com.ims.entitlement.spi;

public class LimitReachedException extends RuntimeException {

    private final Feature feature;
    private final long limit;
    private final long current;

    public LimitReachedException(Feature feature, long limit, long current) {
        super("Limit reached for '" + feature.name() + "': current=" + current + ", limit=" + limit);
        this.feature = feature;
        this.limit = limit;
        this.current = current;
    }

    public Feature getFeature() {
        return feature;
    }

    public long getLimit() {
        return limit;
    }

    public long getCurrent() {
        return current;
    }
}
