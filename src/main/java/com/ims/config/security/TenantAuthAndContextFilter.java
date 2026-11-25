package com.ims.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.common.audit.AuditContext;
import com.ims.entitlement.spi.EntitlementFacade;
import com.ims.entitlement.spi.Feature;
import com.ims.config.tenant.TenantContext;
import com.ims.identity.spi.TenantReadPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Authenticates incoming requests via either:
 * 1) Session JWT ("Bearer <jwt>") + mandatory BFF "X-Proxy-Secret" header.
 *    A stolen session JWT used directly against the backend without X-Proxy-Secret is rejected with 401.
 * 2) API Token ("Bearer ims_pat_<token>") which validates API_ACCESS entitlement and per-token rate limit.
 *
 * Once authenticated, verifies the tenant's status (ACTIVE vs SUSPENDED/DELETED) and binds TenantContext
 * and AuditContext for the lifetime of the request.
 */
@Component
public class TenantAuthAndContextFilter extends OncePerRequestFilter {

    public static final String PROXY_SECRET_HEADER = "X-Proxy-Secret";
    public static final String API_TOKEN_PREFIX = "ims_pat_";

    private record RateWindow(long epochMinute, AtomicInteger count) {}

    private final JwtTokenService jwtTokenService;
    private final TenantReadPort tenantReadPort;
    private final EntitlementFacade EntitlementFacade;
    private final ObjectMapper objectMapper;
    private final String expectedProxySecret;
    private final org.springframework.beans.factory.ObjectProvider<SseTicketTenantLookup> sseTicketLookup;
    private final Map<UUID, RateWindow> tokenRateWindows = new ConcurrentHashMap<>();

    public TenantAuthAndContextFilter(
            JwtTokenService jwtTokenService,
            TenantReadPort tenantReadPort,
            EntitlementFacade EntitlementFacade,
            ObjectMapper objectMapper,
            @Value("${ims.security.proxy-secret:ims-dev-bff-proxy-secret-2026}") String expectedProxySecret,
            org.springframework.beans.factory.ObjectProvider<SseTicketTenantLookup> sseTicketLookup
    ) {
        this.jwtTokenService = jwtTokenService;
        this.tenantReadPort = tenantReadPort;
        this.EntitlementFacade = EntitlementFacade;
        this.objectMapper = objectMapper;
        this.expectedProxySecret = expectedProxySecret;
        this.sseTicketLookup = sseTicketLookup;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/platform/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = Optional.ofNullable(request.getHeader("X-Request-Id"))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
        response.setHeader("X-Request-Id", requestId);
        String clientIp = request.getRemoteAddr();

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // Check SSE ticket for GET /api/v1/events?ticket=...
            if ("/api/v1/events".equals(request.getRequestURI()) && request.getParameter("ticket") != null) {
                SseTicketTenantLookup lookup = sseTicketLookup.getIfAvailable();
                Optional<UUID> ticketTenant = lookup != null ? lookup.peekTenantIdForTicket(request.getParameter("ticket")) : Optional.empty();
                if (ticketTenant.isPresent()) {
                    TenantContext.runAs(ticketTenant.get(), () -> {
                        try {
                            filterChain.doFilter(request, response);
                        } catch (IOException | ServletException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return;
                }
            }
            // Unauthenticated endpoint (e.g., /api/v1/auth/login): run with AuditContext only, no TenantContext bound
            AuditContext.Snapshot anonSnap = new AuditContext.Snapshot(
                    AuditContext.ActorType.USER, null, "ANONYMOUS", null, requestId, clientIp
            );
            AuditContext.runWith(anonSnap, () -> {
                try {
                    filterChain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new RuntimeException(e);
                }
            });
            return;
        }

        String rawToken = authHeader.substring("Bearer ".length()).trim();
        TenantUserPrincipal principal;

        if (rawToken.startsWith(API_TOKEN_PREFIX)) {
            principal = authenticateApiToken(rawToken, response);
            if (principal == null) {
                return;
            }
        } else {
            // Session JWT from BFF â€” MUST carry matching X-Proxy-Secret
            String providedSecret = request.getHeader(PROXY_SECRET_HEADER);
            if (!constantTimeEquals(expectedProxySecret, providedSecret)) {
                writeError(response, HttpStatus.UNAUTHORIZED, "MISSING_OR_INVALID_PROXY_SECRET",
                        "Session JWTs are only accepted through the trusted BFF proxy.");
                return;
            }
            try {
                principal = jwtTokenService.parseTenantAccessToken(rawToken);
            } catch (Exception e) {
                writeError(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid or expired access token.");
                return;
            }
            if (principal.permissions().isEmpty()) {
                writeError(response, HttpStatus.UNAUTHORIZED, "EMPTY_PERMISSIONS_TOKEN",
                        "Session token has empty permissions; trigger token refresh.");
                return;
            }
        }

        // Gate on Tenant status (ACTIVE vs SUSPENDED / DELETED)
        Optional<TenantReadPort.TenantSummary> tenantOpt = tenantReadPort.findById(principal.tenantId());
        if (tenantOpt.isEmpty()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "TENANT_NOT_FOUND", "Tenant not found.");
            return;
        }
        TenantReadPort.TenantSummary tenant = tenantOpt.get();
        if (!"ACTIVE".equals(tenant.status())) {
            writeError(response, HttpStatus.FORBIDDEN, "TENANT_SUSPENDED",
                    "Tenant account is currently " + tenant.status() + ".");
            return;
        }

        // If API token, check API_ACCESS feature entitlement and rate limit
        if (principal.apiTokenAuth()) {
            EntitlementFacade.EffectiveEntitlements entitlements = EntitlementFacade.getEffectiveForTenant(principal.tenantId());
            if (!entitlements.isEnabled(Feature.API_ACCESS)) {
                writeError(response, HttpStatus.FORBIDDEN, "FEATURE_NOT_ENABLED",
                        "Feature 'API_ACCESS' is not enabled for this tenant.");
                return;
            }
            Long rateLimit = entitlements.getLimit(Feature.API_RATE_PER_MIN);
            if (rateLimit != null && !checkRateLimit(principal.apiTokenId(), rateLimit)) {
                writeError(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                        "API token rate limit of " + rateLimit + " requests/min exceeded.");
                return;
            }
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        AuditContext.Snapshot auditSnapshot = new AuditContext.Snapshot(
                principal.apiTokenAuth() ? AuditContext.ActorType.API_TOKEN : AuditContext.ActorType.USER,
                principal.userId(),
                principal.email(),
                null,
                requestId,
                clientIp
        );

        try {
            TenantContext.runAs(principal.tenantId(), () ->
                    AuditContext.runWith(auditSnapshot, () -> {
                        try {
                            filterChain.doFilter(request, response);
                        } catch (IOException | ServletException e) {
                            throw new RuntimeException(e);
                        }
                    })
            );
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException ioEx) {
                throw ioEx;
            }
            if (ex.getCause() instanceof ServletException srvEx) {
                throw srvEx;
            }
            throw ex;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private TenantUserPrincipal authenticateApiToken(String rawToken, HttpServletResponse response) throws IOException {
        String hash = JwtTokenService.sha256Hex(rawToken);
        Optional<TenantReadPort.ApiTokenPrincipal> tokenOpt = tenantReadPort.authenticateApiToken(hash);
        if (tokenOpt.isEmpty()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "INVALID_API_TOKEN", "Invalid, expired, or disabled API token.");
            return null;
        }
        TenantReadPort.ApiTokenPrincipal p = tokenOpt.get();
        return new TenantUserPrincipal(
                p.userId(),
                p.tenantId(),
                p.email(),
                p.fullName(),
                p.scopes(),
                true,
                p.apiTokenId()
        );
    }

    private boolean checkRateLimit(UUID tokenId, long maxPerMinute) {
        long currentMinute = Instant.now().getEpochSecond() / 60;
        RateWindow window = tokenRateWindows.compute(tokenId, (id, existing) -> {
            if (existing == null || existing.epochMinute() != currentMinute) {
                return new RateWindow(currentMinute, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        return window.count().get() <= maxPerMinute;
    }

    private boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "code", code,
                "message", message,
                "timestamp", Instant.now().toString()
        ));
    }
}
