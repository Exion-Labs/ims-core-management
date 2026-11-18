package com.ims.integration;

import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.common.outbox.OutboxEvent;
import com.ims.common.outbox.OutboxEventHandler;
import com.ims.config.security.SseTicketTenantLookup;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseService implements OutboxEventHandler, SseTicketTenantLookup {

    private record TicketInfo(UUID tenantId, UUID userId, Instant expiresAt) {}

    private final Map<String, TicketInfo> tickets = new ConcurrentHashMap<>();
    private final Map<UUID, List<SseEmitter>> emittersByTenant = new ConcurrentHashMap<>();
    private final EntitlementService entitlementService;

    public SseService(EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    public String issueTicket(UUID tenantId, UUID userId) {
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new TicketInfo(tenantId, userId, Instant.now().plusSeconds(60)));
        return ticket;
    }

    @Override
    public java.util.Optional<UUID> peekTenantIdForTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) return java.util.Optional.empty();
        TicketInfo info = tickets.get(ticket);
        if (info == null || info.expiresAt().isBefore(Instant.now())) return java.util.Optional.empty();
        return java.util.Optional.of(info.tenantId());
    }

    public SseEmitter connectWithTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing SSE ticket");
        }
        TicketInfo info = tickets.remove(ticket);
        if (info == null || info.expiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired SSE ticket");
        }
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emittersByTenant.computeIfAbsent(info.tenantId(), k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(info.tenantId(), emitter));
        emitter.onTimeout(() -> removeEmitter(info.tenantId(), emitter));
        emitter.onError(ex -> removeEmitter(info.tenantId(), emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("tenantId", info.tenantId(), "timestamp", Instant.now().toString())));
        } catch (IOException ignored) {
            removeEmitter(info.tenantId(), emitter);
        }
        return emitter;
    }

    private void removeEmitter(UUID tenantId, SseEmitter emitter) {
        List<SseEmitter> list = emittersByTenant.get(tenantId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    @Override
    public boolean supports(String eventType) {
        return true;
    }

    @Override
    public void handle(OutboxEvent event) {
        if (!entitlementService.getEffectiveForTenant(event.getTenantId()).isEnabled(Feature.REALTIME)) {
            return;
        }
        List<SseEmitter> list = emittersByTenant.get(event.getTenantId());
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<String, Object> data = Map.of(
                "id", event.getId(),
                "eventType", event.getEventType(),
                "aggregateType", event.getAggregateType(),
                "aggregateId", event.getAggregateId(),
                "payload", event.getPayload(),
                "occurredAt", event.getCreatedAt().toString()
        );
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event.getEventType()).data(data));
            } catch (Exception ex) {
                removeEmitter(event.getTenantId(), emitter);
            }
        }
    }
}
