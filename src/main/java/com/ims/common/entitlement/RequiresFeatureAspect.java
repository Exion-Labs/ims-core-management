package com.ims.common.entitlement;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Aspect enforcing @RequiresFeature on both controller/service classes and individual methods.
 */
@Aspect
@Component
public class RequiresFeatureAspect {

    private final EntitlementService entitlementService;

    public RequiresFeatureAspect(EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    @Before("@within(com.ims.common.entitlement.RequiresFeature) || @annotation(com.ims.common.entitlement.RequiresFeature)")
    public void enforceFeature(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        RequiresFeature methodAnnotation = AnnotationUtils.findAnnotation(method, RequiresFeature.class);
        if (methodAnnotation != null) {
            entitlementService.requireFeature(methodAnnotation.value());
            return;
        }

        RequiresFeature classAnnotation = AnnotationUtils.findAnnotation(joinPoint.getTarget().getClass(), RequiresFeature.class);
        if (classAnnotation != null) {
            entitlementService.requireFeature(classAnnotation.value());
        }
    }
}
