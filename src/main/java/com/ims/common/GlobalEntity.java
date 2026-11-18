package com.ims.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an @Entity as intentionally global (not tenant-partitioned by Hibernate @TenantId).
 * Enforced by ArchUnit DataAccessRulesTest against an explicit allowlist.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalEntity {
}
