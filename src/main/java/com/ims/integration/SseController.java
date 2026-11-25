package com.ims.integration;

import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.config.security.TenantUserPrincipal;
import com.ims.config.tenant.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@RequiresFeature(Feature.REALTIME)
public class SseController {

    private final SseService sseService;

    public SseController(SseService sseService) {
        this.sseService = sseService;
    }

    @PostMapping("/ticket")
    public Map<String, String> issueTicket(Authentication authentication) {
        UUID tenantId = TenantContext.require();
        UUID userId = (authentication != null && authentication.getPrincipal() instanceof TenantUserPrincipal p)
                ? p.userId()
                : null;
        return Map.of("ticket", sseService.issueTicket(tenantId, userId));
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String ticket) {
        return sseService.connectWithTicket(ticket);
    }
}
