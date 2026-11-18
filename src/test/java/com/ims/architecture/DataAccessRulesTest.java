package com.ims.architecture;

import com.ims.common.BaseTenantEntity;
import com.ims.common.GlobalEntity;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces Data-Access Tenant Safety Rules:
 * 1. No raw SQL at runtime (@Query(nativeQuery=true), EntityManager.createNativeQuery, Session.createNativeQuery,
 *    JdbcTemplate, NamedParameterJdbcTemplate, JdbcClient, DataSource/Connection).
 * 2. No unchecked references (getReferenceById, EntityManager.getReference).
 * 3. Every @Entity must either extend BaseTenantEntity or be annotated @GlobalEntity from the explicit allowlist.
 */
class DataAccessRulesTest {

    private static final Set<String> GLOBAL_ENTITY_ALLOWLIST = Set.of(
            "Tenant",
            "UserAccount",
            "RefreshToken",
            "ApiToken",
            "OutboxEvent",
            "ExchangeRate",
            "PlatformUser",
            "Plan",
            "PlanFeature",
            "TenantSubscription",
            "TenantFeatureOverride",
            "TenantUsageSnapshot",
            "SupportSession",
            "PlatformAuditLog"
    );

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ims");
    }

    @Test
    void everyEntityMustExtendBaseTenantEntityOrBeAllowlistedGlobalEntity() {
        for (JavaClass javaClass : classes) {
            if (!javaClass.isAnnotatedWith(Entity.class)) {
                continue;
            }
            boolean isTenantEntity = javaClass.isAssignableTo(BaseTenantEntity.class);
            boolean isGlobalEntity = javaClass.isAnnotatedWith(GlobalEntity.class);

            assertThat(isTenantEntity || isGlobalEntity)
                    .as("@Entity %s must either extend BaseTenantEntity or be annotated with @GlobalEntity", javaClass.getName())
                    .isTrue();

            assertThat(isTenantEntity && isGlobalEntity)
                    .as("@Entity %s cannot both extend BaseTenantEntity and be @GlobalEntity", javaClass.getName())
                    .isFalse();

            if (isGlobalEntity) {
                assertThat(GLOBAL_ENTITY_ALLOWLIST)
                        .as("@GlobalEntity %s must be in the explicit GLOBAL_ENTITY_ALLOWLIST", javaClass.getSimpleName())
                        .contains(javaClass.getSimpleName());
            }
        }
    }

    @Test
    void noRepositoryMethodMayUseNativeQueryTrue() {
        for (JavaClass javaClass : classes) {
            javaClass.getMethods().forEach(method -> {
                if (method.isAnnotatedWith(Query.class)) {
                    Query q = method.getAnnotationOfType(Query.class);
                    assertThat(q.nativeQuery())
                            .as("Native SQL @Query(nativeQuery = true) is forbidden on %s#%s",
                                    javaClass.getSimpleName(), method.getName())
                            .isFalse();
                }
            });
        }
    }

    @Test
    void noClassMayCallNativeQueryOrUncheckedGetReferenceApis() {
        noClasses()
                .should().callMethod(EntityManager.class, "createNativeQuery", String.class)
                .orShould().callMethod(EntityManager.class, "createNativeQuery", String.class, Class.class)
                .orShould().callMethod(EntityManager.class, "createNativeQuery", String.class, String.class)
                .orShould().callMethod(EntityManager.class, "getReference", Class.class, Object.class)
                .orShould().callMethod(Session.class, "createNativeQuery", String.class)
                .orShould().callMethod(Session.class, "createNativeQuery", String.class, Class.class)
                .orShould().callMethod(Session.class, "createNativeMutationQuery", String.class)
                .orShould().callMethod(JpaRepository.class, "getReferenceById", Object.class)
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void noClassMayUseJdbcTemplatesOrDirectDataSourceConnections() {
        noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
                .orShould().dependOnClassesThat().areAssignableTo(DataSource.class)
                .orShould().dependOnClassesThat().areAssignableTo(Connection.class)
                .allowEmptyShould(true)
                .check(classes);
    }
}
