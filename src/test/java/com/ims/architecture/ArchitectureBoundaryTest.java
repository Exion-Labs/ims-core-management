package com.ims.architecture;

import com.ims.common.audit.AuditEventRepository;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.common.events.DomainEvent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces package boundaries, event isolation, platform separation, and AuditEvent immutability.
 */
class ArchitectureBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ims");
    }

    @Test
    void domainEventsMustResideStrictlyInCommonEventsPackage() {
        classes()
                .that().implement(DomainEvent.class)
                .should().resideInAPackage("com.ims.common.events")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void noDomainPackageMayImportPlatformPackage() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.ims.tenant..",
                        "com.ims.part..",
                        "com.ims.stock..",
                        "com.ims.company..",
                        "com.ims.build..",
                        "com.ims.order..",
                        "com.ims.integration.."
                )
                .should().dependOnClassesThat().resideInAPackage("com.ims.platform..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void tenantControllersMustNeverExposePlatformNamespace() {
        classes.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class))
                .filter(c -> !c.getPackageName().startsWith("com.ims.platform"))
                .forEach(controller -> {
                    if (controller.isAnnotatedWith(RequestMapping.class)) {
                        String[] paths = controller.getAnnotationOfType(RequestMapping.class).value();
                        for (String p : paths) {
                            assertThat(p).doesNotStartWith("/api/platform");
                        }
                    }
                });
    }

    @Test
    void auditEventRepositoryMustForbidUpdateAndDeleteMethods() {
        Set<String> forbiddenPrefixes = Set.of("delete", "remove", "update", "saveAll");
        for (Method m : AuditEventRepository.class.getDeclaredMethods()) {
            for (String prefix : forbiddenPrefixes) {
                assertThat(m.getName())
                        .as("AuditEventRepository must not declare mutating/deleting method: %s", m.getName())
                        .doesNotStartWith(prefix);
            }
        }
    }

    @Test
    void domainFeaturePackagesShouldBeFreeOfCycles() {
        SlicesRuleDefinition.slices()
                .matching("com.ims.(part|stock|company|build|order|integration).(*)..")
                .should().beFreeOfCycles()
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void domainControllersMustBeAnnotatedWithRequiresFeature() {
        classes()
                .that().areAnnotatedWith(RestController.class)
                .and().resideInAnyPackage(
                        "com.ims.part..",
                        "com.ims.stock..",
                        "com.ims.company..",
                        "com.ims.build..",
                        "com.ims.order..",
                        "com.ims.integration.."
                )
                .should().beAnnotatedWith(RequiresFeature.class)
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void partRepositoriesMustOnlyBeAccessedWithinPartPackage() {
        classes()
                .that().resideInAPackage("com.ims.part..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().onlyBeAccessed().byAnyPackage("com.ims.part..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void stockRepositoriesMustOnlyBeAccessedWithinStockPackage() {
        classes()
                .that().resideInAPackage("com.ims.stock..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().onlyBeAccessed().byAnyPackage("com.ims.stock..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void companyRepositoriesMustOnlyBeAccessedWithinCompanyPackage() {
        classes()
                .that().resideInAPackage("com.ims.company..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().onlyBeAccessed().byAnyPackage("com.ims.company..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void orderRepositoriesMustOnlyBeAccessedWithinOrderPackage() {
        classes()
                .that().resideInAPackage("com.ims.order..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().onlyBeAccessed().byAnyPackage("com.ims.order..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void buildRepositoriesMustOnlyBeAccessedWithinBuildPackage() {
        classes()
                .that().resideInAPackage("com.ims.build..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().onlyBeAccessed().byAnyPackage("com.ims.build..")
                .allowEmptyShould(true)
                .check(classes);
    }
}
