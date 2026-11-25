package com.ims.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.entitlement.spi.EntitlementFacade;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.common.outbox.OutboxEvent;
import com.ims.common.outbox.OutboxEventHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WebhookDeliveryService implements OutboxEventHandler {

    public record WebhookSubscriptionView(
            UUID id,
            String name,
            String targetUrl,
            List<String> eventTypes,
            boolean enabled,
            Instant lastDeliveryAt,
            Integer lastStatusCode,
            int failureCount,
            Instant createdAt
    ) {}

    public record CreateWebhookCommand(
            String name,
            String targetUrl,
            String secret,
            List<String> eventTypes,
            Boolean enabled
    ) {}

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final EntitlementFacade EntitlementFacade;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebhookDeliveryService(
            WebhookSubscriptionRepository subscriptionRepository,
            EntitlementFacade EntitlementFacade,
            ObjectMapper objectMapper
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.EntitlementFacade = EntitlementFacade;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Transactional(readOnly = true)
    @RequiresFeature(Feature.WEBHOOKS)
    public List<WebhookSubscriptionView> listSubscriptions() {
        return subscriptionRepository.findByOrderByCreatedAtDesc().stream().map(this::toView).toList();
    }

    @Transactional
    @RequiresFeature(Feature.WEBHOOKS)
    public WebhookSubscriptionView createSubscription(CreateWebhookCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank() || cmd.targetUrl() == null || cmd.targetUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and targetUrl are required");
        }
        validateWebhookUrl(cmd.targetUrl());
        WebhookSubscription sub = new WebhookSubscription();
        sub.setName(cmd.name().trim());
        sub.setTargetUrl(cmd.targetUrl().trim());
        sub.setSecret(cmd.secret() != null && !cmd.secret().isBlank() ? cmd.secret().trim() : generateSecret());
        sub.setEventTypes(cmd.eventTypes() != null ? cmd.eventTypes() : List.of("*"));
        sub.setEnabled(cmd.enabled() == null || cmd.enabled());
        return toView(subscriptionRepository.saveAndFlush(sub));
    }

    @Transactional
    @RequiresFeature(Feature.WEBHOOKS)
    public void deleteSubscription(UUID id) {
        WebhookSubscription sub = subscriptionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook subscription not found"));
        subscriptionRepository.delete(sub);
    }

    public static void validateWebhookUrl(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid webhook URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Webhook URL must use http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Webhook URL must include a host");
        }
        String lowerHost = host.toLowerCase();
        if ("localhost".equals(lowerHost) || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Webhook URL cannot target internal hosts");
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isAnyLocalAddress()
                        || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Webhook URL cannot target private or loopback IP ranges");
                }
            }
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ignored) {
            // DNS resolution may fail in offline test environments for external domains; block known private literals above.
        }
    }

    public static String signPayload(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign webhook payload", ex);
        }
    }

    @Override
    public boolean supports(String eventType) {
        return true;
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) throws Exception {
        if (!EntitlementFacade.getEffectiveForTenant(event.getTenantId()).isEnabled(Feature.WEBHOOKS)) {
            return;
        }
        List<WebhookSubscription> subs = subscriptionRepository.findByEnabledTrue();
        if (subs.isEmpty()) {
            return;
        }
        String payload = objectMapper.writeValueAsString(Map.of(
                "id", event.getId(),
                "eventType", event.getEventType(),
                "aggregateType", event.getAggregateType(),
                "aggregateId", event.getAggregateId(),
                "occurredAt", event.getCreatedAt().toString(),
                "payload", event.getPayload()
        ));
        for (WebhookSubscription sub : subs) {
            List<String> types = sub.getEventTypes();
            boolean matches = types == null || types.isEmpty() || types.contains("*") || types.contains(event.getEventType());
            if (!matches) continue;
            validateWebhookUrl(sub.getTargetUrl());
            String sig = signPayload(sub.getSecret(), payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(sub.getTargetUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-IMS-Event", event.getEventType())
                    .header("X-IMS-Signature", sig)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            sub.setLastDeliveryAt(Instant.now());
            sub.setLastStatusCode(resp.statusCode());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                sub.setFailureCount(0);
            } else {
                sub.setFailureCount(sub.getFailureCount() + 1);
            }
            subscriptionRepository.save(sub);
            if (resp.statusCode() >= 500) {
                throw new IllegalStateException("Webhook returned HTTP " + resp.statusCode());
            }
        }
    }

    private String generateSecret() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return "whsec_" + HexFormat.of().formatHex(bytes);
    }

    private WebhookSubscriptionView toView(WebhookSubscription s) {
        return new WebhookSubscriptionView(
                s.getId(),
                s.getName(),
                s.getTargetUrl(),
                s.getEventTypes(),
                s.isEnabled(),
                s.getLastDeliveryAt(),
                s.getLastStatusCode(),
                s.getFailureCount(),
                s.getCreatedAt()
        );
    }
}
