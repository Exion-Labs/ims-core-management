package com.ims.integration;

import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiresFeature(Feature.WEBHOOKS)
public class WebhookController {

    private final WebhookDeliveryService webhookService;

    public WebhookController(WebhookDeliveryService webhookService) {
        this.webhookService = webhookService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    public List<WebhookDeliveryService.WebhookSubscriptionView> list() {
        return webhookService.listSubscriptions();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADMIN:CREATE')")
    public WebhookDeliveryService.WebhookSubscriptionView create(@Valid @RequestBody WebhookDeliveryService.CreateWebhookCommand cmd) {
        return webhookService.createSubscription(cmd);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ADMIN:DELETE')")
    public void delete(@PathVariable UUID id) {
        webhookService.deleteSubscription(id);
    }
}
