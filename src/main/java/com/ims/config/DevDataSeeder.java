package com.ims.config;

import com.ims.config.tenant.TenantContext;
import com.ims.entitlement.spi.EntitlementAdminPort;
import com.ims.identity.spi.TenantProvisioningPort;
import com.ims.identity.spi.TenantReadPort;
import com.ims.catalog.application.PartCategoryService;
import com.ims.catalog.application.PartService;
import com.ims.stock.application.StockLocationService;
import com.ims.stock.application.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds two demo tenants (Tenant A on STARTER, Tenant B on PRO) and sample catalog & stock data
 * so login, multi-tenant isolation, RBAC, and the Part Catalog can be explored immediately.
 */
@Component
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final TenantProvisioningPort tenantProvisioningPort;
    private final TenantReadPort tenantReadPort;
    private final EntitlementAdminPort entitlementAdminPort;
    private final PartCategoryService categoryService;
    private final PartService partService;
    private final StockLocationService stockLocationService;
    private final StockService stockService;

    public DevDataSeeder(
            TenantProvisioningPort tenantProvisioningPort,
            TenantReadPort tenantReadPort,
            EntitlementAdminPort entitlementAdminPort,
            PartCategoryService categoryService,
            PartService partService,
            StockLocationService stockLocationService,
            StockService stockService
    ) {
        this.tenantProvisioningPort = tenantProvisioningPort;
        this.tenantReadPort = tenantReadPort;
        this.entitlementAdminPort = entitlementAdminPort;
        this.categoryService = categoryService;
        this.partService = partService;
        this.stockLocationService = stockLocationService;
        this.stockService = stockService;
    }

    @Override
    public void run(String... args) {
        entitlementAdminPort.ensureDefaultPlansSeeded();

        if (!tenantReadPort.existsBySlug("acme-robotics")) {
            TenantProvisioningPort.AuthTokensResponse acme = tenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
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
        } else {
            tenantReadPort.findBySlug("acme-robotics").ifPresent(t -> ensureBusinessSampleItems(t.id()));
        }

        if (!tenantReadPort.existsBySlug("nordic-labs")) {
            TenantProvisioningPort.AuthTokensResponse nordic = tenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
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
            ensureBusinessSampleItems(nordic.tenantId());
        } else {
            tenantReadPort.findBySlug("nordic-labs").ifPresent(t -> ensureBusinessSampleItems(t.id()));
        }
    }

    private void ensureBusinessSampleItems(UUID tenantId) {
        TenantContext.runAs(tenantId, () -> {
            var existingCats = categoryService.listCategories();
            boolean hasPosCategory = existingCats.stream().anyMatch(c -> "POS & Hardware".equalsIgnoreCase(c.name()));
            if (hasPosCategory) {
                return;
            }

            var mainWarehouse = stockLocationService.listLocations().stream()
                    .filter(l -> !l.structural())
                    .findFirst()
                    .orElseGet(() -> stockLocationService.createLocation(new StockLocationService.CreateLocationCommand(
                            "Main Storefront & Warehouse",
                            "Primary stockroom and retail shelf storage",
                            null,
                            "WAREHOUSE",
                            false,
                            false,
                            "LOC-MAIN-01"
                    )));

            var posCat = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "POS & Hardware", "Point of sale terminals, scanners, and printers", null, false, mainWarehouse.id(), "box"
            ));
            var scannersCat = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "Barcode Scanners", "Handheld 1D/2D and desktop barcode scanners", posCat.id(), false, mainWarehouse.id(), "qr-code"
            ));
            var printersCat = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "Receipt & Label Printers", "Thermal receipt and shipping label printers", posCat.id(), false, mainWarehouse.id(), "printer"
            ));
            var suppliesCat = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "Packaging & Supplies", "Boxes, thermal paper rolls, and shipping labels", null, false, mainWarehouse.id(), "package"
            ));
            var labelsCat = categoryService.createCategory(new PartCategoryService.CreateCategoryCommand(
                    "Thermal Rolls & Labels", "Direct thermal and thermal transfer consumables", suppliesCat.id(), false, mainWarehouse.id(), "tag"
            ));

            var scanner1 = partService.createPart(new PartService.CreatePartCommand(
                    "SCN-2D-PRO",
                    "Wireless 2D QR & Barcode Scanner",
                    "Bluetooth & 2.4GHz USB cordless handheld scanner with charging cradle",
                    scannersCat.id(),
                    "A", null, null, "pcs", new BigDecimal("15"),
                    true, false, false, true, true, true, true, false,
                    Map.of(), List.of()
            ));
            stockService.createStock(new StockService.CreateStockCommand(
                    scanner1.id(), mainWarehouse.id(), new BigDecimal("42"),
                    null, "BATCH-2026-10", "OK", null, new BigDecimal("68.00"), "USD", "Retail Box", false, null, "Initial stock seed"
            ));

            var printer1 = partService.createPart(new PartService.CreatePartCommand(
                    "PRT-THERM-80",
                    "80mm Thermal Receipt Printer (USB/LAN)",
                    "High-speed 250mm/s auto-cutter POS receipt printer",
                    printersCat.id(),
                    "A", null, null, "pcs", new BigDecimal("10"),
                    true, false, false, true, true, true, true, false,
                    Map.of(), List.of()
            ));
            stockService.createStock(new StockService.CreateStockCommand(
                    printer1.id(), mainWarehouse.id(), new BigDecimal("6"),
                    null, "BATCH-2026-11", "OK", null, new BigDecimal("115.00"), "USD", "Box", false, null, "Low stock seed"
            ));

            var paperRoll = partService.createPart(new PartService.CreatePartCommand(
                    "SUP-ROLL-80X80",
                    "Thermal Receipt Paper Rolls 80x80mm (Box of 50)",
                    "BPA-free high sensitivity thermal paper rolls",
                    labelsCat.id(),
                    "A", null, null, "box", new BigDecimal("20"),
                    true, false, false, true, false, true, true, false,
                    Map.of(), List.of()
            ));
            stockService.createStock(new StockService.CreateStockCommand(
                    paperRoll.id(), mainWarehouse.id(), new BigDecimal("85"),
                    null, "LOT-PAPER-01", "OK", null, new BigDecimal("34.50"), "USD", "Carton", false, null, "Initial stock seed"
            ));

            var shippingLabels = partService.createPart(new PartService.CreatePartCommand(
                    "SUP-LBL-4X6",
                    "4x6 Direct Thermal Shipping Labels (Roll of 500)",
                    "Perforated self-adhesive fanfold/roll labels for courier waybills",
                    labelsCat.id(),
                    "A", null, null, "roll", new BigDecimal("30"),
                    true, false, false, true, false, true, true, false,
                    Map.of(), List.of()
            ));
            stockService.createStock(new StockService.CreateStockCommand(
                    shippingLabels.id(), mainWarehouse.id(), new BigDecimal("18"),
                    null, "LOT-LBL-09", "OK", null, new BigDecimal("9.20"), "USD", "Roll", false, null, "Below min stock seed"
            ));

            partService.createPart(new PartService.CreatePartCommand(
                    "POS-DRAWER-16",
                    "Heavy-Duty 16\" Cash Drawer (RJ11)",
                    "5-bill / 8-coin removable tray steel cash register drawer",
                    posCat.id(),
                    "A", null, null, "pcs", new BigDecimal("8"),
                    true, false, false, true, false, true, true, false,
                    Map.of(), List.of()
            ));
        });
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
