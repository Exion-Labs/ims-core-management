package com.ims.config;

import com.ims.common.idempotency.IdempotencyAspect;
import com.ims.common.idempotency.IdempotencyExempt;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI imsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("IMS Multi-Tenant API")
                        .version("0.1.0")
                        .description("Multi-Tenant Inventory Management System REST API"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    @Bean
    public GroupedOpenApi tenantApi() {
        return GroupedOpenApi.builder()
                .group("tenant")
                .pathsToMatch("/api/v1/**")
                .addOperationCustomizer(idempotencyHeaderCustomizer())
                .build();
    }

    private OperationCustomizer idempotencyHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            boolean isPostOrPatch = AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), PostMapping.class)
                    || AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), PatchMapping.class);
            boolean isExempt = AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), IdempotencyExempt.class)
                    || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), IdempotencyExempt.class);
            if (isPostOrPatch && !isExempt) {
                operation.addParametersItem(new Parameter()
                        .in("header")
                        .name(IdempotencyAspect.HEADER)
                        .required(false)
                        .description("Optional client-generated key (e.g. UUID) to make this mutation idempotent for 24 hours.")
                        .schema(new StringSchema().maxLength(255)));
            }
            return operation;
        };
    }

    @Bean
    public GroupedOpenApi platformApi() {
        return GroupedOpenApi.builder()
                .group("platform")
                .pathsToMatch("/api/platform/v1/**")
                .build();
    }
}
