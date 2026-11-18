package com.ims.integration;

import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/parts")
@RequiresFeature(Feature.PRICING_ROLLUP)
public class PartPricingController {

    private final PartPricingService pricingService;

    public PartPricingController(PartPricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping("/{partId}/pricing")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public PartPricingService.PartPricingView getPricing(
            @PathVariable UUID partId,
            @RequestParam(required = false, defaultValue = "1") BigDecimal quantity
    ) {
        return pricingService.computePricing(partId, quantity);
    }
}
