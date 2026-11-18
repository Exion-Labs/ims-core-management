package com.ims.config;

import com.ims.config.tenant.TenantContext;
import com.ims.part.PartCategoryService;
import com.ims.part.PartService;
import com.ims.tenant.AuthService;
import com.ims.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Seeds two demo tenants (Tenant A on STARTER, Tenant B on PRO) and sample Phase 1 catalog data
 * so login, multi-tenant isolation, RBAC, and the Part Catalog can be explored immediately.
 */
@Component
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final AuthService authService;
    private final TenantRepository tenantRepository;
    private final PartCategoryService categoryService;
    private final PartService partService;

    public DevDataSeeder(
            AuthService authService,
            TenantRepository tenantRepository,
            PartCategoryService categoryService,
            PartService partService
    ) {
        this.authService = authService;
        this.tenantRepository = tenantRepository;
        this.categoryService = categoryService;
        this.partService = partService;
    }

    @Override
    public void run(String... args) {
        authService.ensureDefaultPlansSeeded();

        if (!tenantRepository.existsBySlug("acme-robotics")) {
            AuthService.AuthTokensResponse acme = authService.registerTenant(new AuthService.RegisterTenantCommand(
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

            seedAcmeCatalog(acme.tenantId());
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

    private void seedAcmeCatalog(java.util.UUID tenantId) {
        TenantContext.runAs(tenantId, () -> {
            var electronics = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "Electronics", "Root structural category for electronic components", null, true, null, "cpu"
            ));
            var passives = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "Passives", "Passive surface-mount components", electronics.id(), false, null, "layers"
            ));
            var capacitors = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "Capacitors", "MLCC, tantalum, and electrolytic capacitors", passives.id(), false, null, "zap"
            ));
            var resistors = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "Resistors", "Precision thin-film and thick-film resistors", passives.id(), false, null, "sliders"
            ));
            var mcus = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "Microcontrollers", "32-bit ARM Cortex MCUs", electronics.id(), false, null, "cpu"
            ));

            var capTpl = partService.createParameterTemplate(new PartService.CreateParameterTemplateCommand(
                    "Capacitance", "F", "Nominal capacitance in Farads", null, false, capacitors.id()
            ));
            var resTpl = partService.createParameterTemplate(new PartService.CreateParameterTemplateCommand(
                    "Resistance", "Ohm", "Nominal DC resistance in Ohms", null, false, resistors.id()
            ));
            var voltTpl = partService.createParameterTemplate(new PartService.CreateParameterTemplateCommand(
                    "Voltage Rating", "V", "Maximum rated DC voltage", null, false, passives.id()
            ));
            var pkgTpl = partService.createParameterTemplate(new PartService.CreateParameterTemplateCommand(
                    "Package", null, "SMD footprint package", "0402,0603,0805,1206,LQFP-48", false, null
            ));

            var baseCap = partService.createPart(new PartService.CreatePartCommand(
                    "CAP-MLCC-X7R",
                    "MLCC Capacitor Family X7R",
                    "Base template for X7R ceramic capacitors",
                    capacitors.id(),
                    "A", null, null, "pcs", new BigDecimal("500"),
                    true, false, false, true, false, true, false, false,
                    Map.of("series", "X7R"),
                    List.of(new PartService.InitialParameterInput(voltTpl.id(), "25 V"))
            ));

            partService.createPart(new PartService.CreatePartCommand(
                    "CAP-0402-4U7",
                    "MLCC 4.7uF 25V 0402 X7R",
                    "Decoupling ceramic capacitor 4.7uF",
                    capacitors.id(),
                    "A", null, baseCap.id(), "pcs", new BigDecimal("1000"),
                    true, false, false, true, false, true, false, false,
                    Map.of(),
                    List.of(
                            new PartService.InitialParameterInput(capTpl.id(), "4.7 uF"),
                            new PartService.InitialParameterInput(voltTpl.id(), "25 V"),
                            new PartService.InitialParameterInput(pkgTpl.id(), "0402")
                    )
            ));

            partService.createPart(new PartService.CreatePartCommand(
                    "CAP-0603-10U",
                    "MLCC 10uF 16V 0603 X5R",
                    "Bulk rail bypass capacitor 10uF",
                    capacitors.id(),
                    "A", null, baseCap.id(), "pcs", new BigDecimal("500"),
                    true, false, false, true, false, true, false, false,
                    Map.of(),
                    List.of(
                            new PartService.InitialParameterInput(capTpl.id(), "10 uF"),
                            new PartService.InitialParameterInput(voltTpl.id(), "16 V"),
                            new PartService.InitialParameterInput(pkgTpl.id(), "0603")
                    )
            ));

            partService.createPart(new PartService.CreatePartCommand(
                    "RES-0402-10K",
                    "Thin Film Resistor 10kOhm 0.1% 0402",
                    "Precision pull-up / divider resistor",
                    resistors.id(),
                    "A", null, null, "pcs", new BigDecimal("2000"),
                    true, false, false, true, false, true, false, false,
                    Map.of(),
                    List.of(
                            new PartService.InitialParameterInput(resTpl.id(), "10 kOhm"),
                            new PartService.InitialParameterInput(pkgTpl.id(), "0402")
                    )
            ));

            partService.createPart(new PartService.CreatePartCommand(
                    "MCU-STM32G474",
                    "STM32G474RET6 Mixed-Signal MCU",
                    "170 MHz Cortex-M4F microcontroller for motor control assembly",
                    mcus.id(),
                    "A", null, null, "pcs", new BigDecimal("50"),
                    true, false, false, true, true, true, false, false,
                    Map.of(),
                    List.of(
                            new PartService.InitialParameterInput(voltTpl.id(), "3.3 V"),
                            new PartService.InitialParameterInput(pkgTpl.id(), "LQFP-48")
                    )
            ));
        });
    }
}
