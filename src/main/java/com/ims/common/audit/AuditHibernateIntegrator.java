package com.ims.common.audit;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;

/**
 * Registers AuditEventListener with Hibernate's EventListenerRegistry at SessionFactory bootstrap.
 */
public class AuditHibernateIntegrator implements Integrator {

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext, SessionFactoryImplementor sessionFactory) {
        EventListenerRegistry registry = sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
        if (registry != null) {
            AuditEventListener listener = new AuditEventListener();
            registry.appendListeners(EventType.POST_INSERT, listener);
            registry.appendListeners(EventType.POST_UPDATE, listener);
            registry.appendListeners(EventType.POST_DELETE, listener);
        }
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory, org.hibernate.service.spi.SessionFactoryServiceRegistry serviceRegistry) {
    }
}
