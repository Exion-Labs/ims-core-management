package com.ims.config;

import com.ims.tenant.AuthService;
import com.ims.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds two demo tenants (Tenant A on STARTER, Tenant B on PRO) in the dev profile
 * so multi-tenant isolation, RBAC, and feature entitlements can be explored immediately.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final AuthService authService;
    private final TenantRepository tenantRepository;

    public DevDataSeeder(AuthService authService, TenantRepository tenantRepository) {
        this.authService = authService;
        this.tenantRepository = tenantRepository;
    }

    @Override
    public void run(String... args) {
        authService.ensureDefaultPlansSeeded();

        if (!tenantRepository.existsBySlug("acme-robotics")) {
            authService.registerTenant(new AuthService.RegisterTenantCommand(
                    "Acme Robotics Ltd",
                    "acme-robotics",
                    "admin@acme-robotics.dev",
                    "Alice Vance",
                    "Password123!",
                    "USD",
                    "America/New_York",
                    "PRO"
            ), true);
            log.info("Seeded Demo Tenant B (PRO): admin@acme-robotics.dev / Password123!");
        }

        if (!tenantRepository.existsBySlug("nordic-labs")) {
            authService.registerTenant(new AuthService.RegisterTenantCommand(
                    "Nordic Sensor Labs",
                    "nordic-labs",
                    "admin@nordic-labs.dev",
                    "Erik Lindqvist",
                    "Password123!",
                    "EUR",
                    "Europe/Stockholm",
                    "STARTER"
            ), true);
            log.info("Seeded Demo Tenant A (STARTER): admin@nordic-labs.dev / Password123!");
        }
    }
}
