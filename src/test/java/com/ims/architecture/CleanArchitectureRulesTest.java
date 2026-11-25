package com.ims.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces Modular Monolith & Clean/Hexagonal layering rules across migrated bounded contexts.
 * As each phase migrates a module into the target structure, it is added to MIGRATED_MODULES.
 */
class CleanArchitectureRulesTest {

    private static final String[] MIGRATED_MODULES = {
            "com.ims.entitlement..",
            "com.ims.identity..",
            "com.ims.collaboration..",
            "com.ims.catalog..",
            "com.ims.partner..",
            "com.ims.stock..",
            "com.ims.purchasing..",
            "com.ims.manufacturing..",
            "com.ims.fulfillment.."
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ims");
    }

    @Test
    void sharedKernelMustNeverDependOnBusinessModules() {
        noClasses()
                .that().resideInAPackage("com.ims.shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ims.catalog..",
                        "com.ims.stock..",
                        "com.ims.partner..",
                        "com.ims.manufacturing..",
                        "com.ims.purchasing..", "com.ims.fulfillment..",
                        "com.ims.integration..",
                        "com.ims.platform..",
                        "com.ims.tenant..",
                        "com.ims.entitlement..",
                        "com.ims.identity..",
                        "com.ims.collaboration..",
                        "com.ims.catalog..",
                        "com.ims.partner..",
                        "com.ims.purchasing..",
                        "com.ims.manufacturing..",
                        "com.ims.fulfillment.."
                )
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void migratedEntitiesMustResideInDomainPackages() {
        classes()
                .that().areAnnotatedWith(Entity.class)
                .and().resideInAnyPackage(MIGRATED_MODULES)
                .should().resideInAPackage("..domain..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void migratedDomainAndApplicationLayersMustNotUseResponseStatusException() {
        noClasses()
                .that().resideInAnyPackage(MIGRATED_MODULES)
                .and().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().areAssignableTo(ResponseStatusException.class)
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void migratedControllersMustResideInApiPackage() {
        classes()
                .that().areAnnotatedWith(RestController.class)
                .and().resideInAnyPackage(MIGRATED_MODULES)
                .should().resideInAPackage("..api..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void migratedDomainLayerMustNotDependOnApiOrInfrastructure() {
        noClasses()
                .that().resideInAnyPackage(MIGRATED_MODULES)
                .and().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void purchasingManufacturingAndFulfillmentMustOnlyAccessSiblingModulesViaSpi() {
        noClasses()
                .that().resideInAnyPackage("com.ims.purchasing..", "com.ims.manufacturing..", "com.ims.fulfillment..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.ims.catalog.domain..", "com.ims.catalog.application..", "com.ims.catalog.infrastructure..", "com.ims.catalog.api..",
                        "com.ims.partner.domain..", "com.ims.partner.application..", "com.ims.partner.infrastructure..", "com.ims.partner.api..",
                        "com.ims.stock.domain..", "com.ims.stock.application..", "com.ims.stock.infrastructure..", "com.ims.stock.api..",
                        "com.ims.identity.domain..", "com.ims.identity.application..", "com.ims.identity.infrastructure..", "com.ims.identity.api..",
                        "com.ims.entitlement.domain..", "com.ims.entitlement.application..", "com.ims.entitlement.infrastructure.."
                )
                .allowEmptyShould(true)
                .check(classes);
    }
}
