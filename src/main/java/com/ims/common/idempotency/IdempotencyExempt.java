package com.ims.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a controller (or a single handler) out of the default-on idempotency layer.
 * A reason is mandatory; ArchUnit fails the build if it is blank.
 * Required for POST/PATCH handlers whose responses cannot be replayed (streams, binary payloads,
 * multipart uploads, SSE), and appropriate for read-only operations exposed via POST.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface IdempotencyExempt {

    String reason();
}
