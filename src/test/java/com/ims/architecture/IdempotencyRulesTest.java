package com.ims.architecture;

import com.ims.common.idempotency.IdempotencyExempt;
import com.ims.common.idempotency.IdempotencyRecordRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the default-on idempotency layer:
 * every tenant POST/PATCH handler is covered by {@code IdempotencyAspect} unless it carries an
 * {@link IdempotencyExempt} with a reason, and handlers whose responses cannot be replayed must be exempt.
 */
class IdempotencyRulesTest {

    private static final List<String> UNREPLAYABLE_MARKERS = List.of(
            "byte[]", "Resource", "StreamingResponseBody", "SseEmitter", "MultipartFile", "InputStream");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ims");
    }

    private static Stream<JavaClass> tenantControllers() {
        return classes.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class))
                .filter(c -> !c.getPackageName().startsWith("com.ims.platform"));
    }

    private static boolean isMutating(JavaMethod method) {
        return method.isAnnotatedWith(PostMapping.class) || method.isAnnotatedWith(PatchMapping.class);
    }

    private static boolean isExempt(JavaClass controller, JavaMethod method) {
        return method.isAnnotatedWith(IdempotencyExempt.class) || controller.isAnnotatedWith(IdempotencyExempt.class);
    }

    @Test
    void everyExemptionMustDeclareANonBlankReason() {
        for (JavaClass javaClass : classes) {
            if (javaClass.isAnnotatedWith(IdempotencyExempt.class)) {
                assertThat(javaClass.getAnnotationOfType(IdempotencyExempt.class).reason().trim())
                        .as("@IdempotencyExempt on %s needs a reason", javaClass.getName())
                        .isNotEmpty();
            }
            for (JavaMethod method : javaClass.getMethods()) {
                if (method.isAnnotatedWith(IdempotencyExempt.class)) {
                    assertThat(method.getAnnotationOfType(IdempotencyExempt.class).reason().trim())
                            .as("@IdempotencyExempt on %s needs a reason", method.getFullName())
                            .isNotEmpty();
                }
            }
        }
    }

    @Test
    void unreplayableMutatingHandlersMustBeExempt() {
        tenantControllers().forEach(controller -> {
            for (JavaMethod method : controller.getMethods()) {
                if (!isMutating(method) || isExempt(controller, method)) {
                    continue;
                }
                String signature = method.getReturnType().getName() + " "
                        + method.getRawParameterTypes().stream().map(JavaClass::getName).toList();
                for (String marker : UNREPLAYABLE_MARKERS) {
                    assertThat(signature)
                            .as("%s returns/accepts a non-replayable type (%s); add @IdempotencyExempt(reason=...)",
                                    method.getFullName(), marker)
                            .doesNotContain(marker);
                }
            }
        });
    }

    @Test
    void mutatingHandlersMustUsePostOrPatchMappingSoTheAspectPointcutCoversThem() {
        tenantControllers().forEach(controller -> {
            for (JavaMethod method : controller.getMethods()) {
                if (!method.isAnnotatedWith(RequestMapping.class)) {
                    continue;
                }
                RequestMethod[] verbs = method.getAnnotationOfType(RequestMapping.class).method();
                for (RequestMethod verb : verbs) {
                    assertThat(verb)
                            .as("%s must use @PostMapping/@PatchMapping instead of @RequestMapping(method=%s)",
                                    method.getFullName(), verb)
                            .isNotIn(RequestMethod.POST, RequestMethod.PATCH);
                }
            }
        });
    }

    @Test
    void idempotencyRecordRepositoryMustNotDeclareUpdateMethods() {
        for (Method m : IdempotencyRecordRepository.class.getDeclaredMethods()) {
            assertThat(m.getName())
                    .as("IdempotencyRecordRepository.%s", m.getName())
                    .doesNotStartWith("update");
        }
    }
}
