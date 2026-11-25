package com.ims.entitlement.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enforces that the current tenant has the specified Feature enabled in their effective entitlements.
 * Can be placed on controller classes/methods and service classes/methods.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {
    Feature value();
}
