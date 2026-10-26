package com.example.hotreload.hibernate;

import com.example.hotreload.HotReloadAgent;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spring component that bridges Hibernate's SessionFactory with the JVM agent.
 *
 * Registers {@link HibernateEntityReloader} so entity class changes trigger
 * cache eviction without restarting the SessionFactory.
 *
 * Add this to your Spring Boot app's component scan and put the agent jar on
 * the classpath with -javaagent.
 */
@Component
public class HibernateAgentIntegration {

    @Autowired
    public HibernateAgentIntegration(SessionFactory sessionFactory) {
        HibernateEntityReloader reloader = new HibernateEntityReloader();
        reloader.setSessionFactory(sessionFactory);
        HotReloadAgent.addReloadListener(reloader);

        System.out.println("[HibernateAgentIntegration] Hibernate hot-reload bridge active.");
    }
}
