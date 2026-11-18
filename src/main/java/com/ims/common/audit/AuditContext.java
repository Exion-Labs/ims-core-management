package com.ims.common.audit;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Holds request/job actor metadata for automatic and explicit AuditEvent recording.
 */
public final class AuditContext {

    public enum ActorType {
        USER,
        API_TOKEN,
        SYSTEM_JOB,
        PLATFORM_SUPPORT
    }

    public record Snapshot(
            ActorType actorType,
            UUID actorId,
            String actorName,
            UUID supportSessionId,
            String requestId,
            String ip
    ) {
        public static Snapshot systemJob(String jobName) {
            return new Snapshot(ActorType.SYSTEM_JOB, null, jobName, null, UUID.randomUUID().toString(), null);
        }
    }

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private AuditContext() {
    }

    public static Snapshot capture() {
        Snapshot s = CURRENT.get();
        if (s != null) {
            return s;
        }
        return new Snapshot(ActorType.SYSTEM_JOB, null, "SYSTEM", null, MDC.get("requestId"), null);
    }

    public static UUID currentActorId() {
        Snapshot s = CURRENT.get();
        return s != null ? s.actorId() : null;
    }

    public static void runWith(Snapshot snapshot, Runnable action) {
        Snapshot prev = CURRENT.get();
        String prevReqId = MDC.get("requestId");
        try {
            CURRENT.set(snapshot);
            if (snapshot != null && snapshot.requestId() != null) {
                MDC.put("requestId", snapshot.requestId());
            }
            action.run();
        } finally {
            if (prev != null) {
                CURRENT.set(prev);
            } else {
                CURRENT.remove();
            }
            if (prevReqId != null) {
                MDC.put("requestId", prevReqId);
            } else {
                MDC.remove("requestId");
            }
        }
    }
}
