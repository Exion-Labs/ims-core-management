package com.ims.entitlement.infrastructure.aspect;

import com.ims.entitlement.spi.EntitlementFacade;
import com.ims.entitlement.spi.RequiresFeature;
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

    private final EntitlementFacade entitlementFacade;

    public RequiresFeatureAspect(EntitlementFacade entitlementFacade) {
        this.entitlementFacade = entitlementFacade;
    }

    @Before("@within(com.ims.entitlement.spi.RequiresFeature) || @annotation(com.ims.entitlement.spi.RequiresFeature)")
    public void enforceFeature(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        RequiresFeature methodAnnotation = AnnotationUtils.findAnnotation(method, RequiresFeature.class);
        if (methodAnnotation != null) {
            entitlementFacade.requireFeature(methodAnnotation.value());
            return;
        }

        RequiresFeature classAnnotation = AnnotationUtils.findAnnotation(joinPoint.getTarget().getClass(), RequiresFeature.class);
        if (classAnnotation != null) {
            entitlementFacade.requireFeature(classAnnotation.value());
        }
    }
}
