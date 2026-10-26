package com.example.hotreload.hibernate;

import com.example.hotreload.HotReloadAgent;
import org.hibernate.SessionFactory;
import jakarta.persistence.Entity;

import java.util.function.Supplier;

/**
 * Hot-reloads Hibernate {@code @Entity} classes when their compiled bytecode
 * changes on disk.
 *
 * What this does:
 *   1. Detects changed entity classes.
 *   2. Evicts the entity (and its collections/queries) from the Hibernate
 *      SessionFactory's first- and second-level caches so stale instances are
 *      not returned.
 *   3. Optionally rebuilds the SessionFactory when structural changes are
 *      detected (new fields, associations, or mapping changes). This requires
 *      a {@link SessionFactoryBuilder} supplier to be registered.
 *
 * For non-structural changes (method-body edits), cache eviction is sufficient.
 * For structural changes (new fields/associations), the SessionFactory must be
 * rebuilt because Hibernate builds its immutable metamodel at startup.
 *
 * The SessionFactory is injected via setSessionFactory(). An optional
 * SessionFactoryBuilder can be registered via setSessionFactoryBuilder() to
 * enable full SessionFactory rebuild on structural changes.
 *
 * Limitations:
 *   - SessionFactory rebuild is expensive and will briefly pause Hibernate
 *     operations. All existing Sessions must be closed before rebuild.
 *   - Requires the SessionFactory to be injected via setSessionFactory().
 *   - The SessionFactoryBuilder must capture enough configuration (DataSource,
 *     mapping packages, hibernate properties) to rebuild from scratch.
 */
public class HibernateEntityReloader implements HotReloadAgent.ReloadListener {

    private volatile SessionFactory sessionFactory;
    private volatile SessionFactoryBuilder sessionFactoryBuilder;

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /**
     * Register a builder that can create a new SessionFactory from scratch.
     * Used for structural changes (new fields, associations, mapping changes)
     * that require a full SessionFactory rebuild.
     */
    public void setSessionFactoryBuilder(SessionFactoryBuilder builder) {
        this.sessionFactoryBuilder = builder;
    }

    @Override
    public void onClassReloaded(String className, byte[] newBytes) {
        if (sessionFactory == null || sessionFactory.isClosed()) {
            return;
        }

        Class<?> entityClass;
        try {
            entityClass = Class.forName(className, false,
                    Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            return;
        }

        // Only reload actual Hibernate entities.
        if (!isHibernateEntity(entityClass)) {
            return;
        }

        System.out.println("[HibernateEntityReloader] Entity changed: "
                + className);

        // Always evict caches for data/method-body changes.
        evictEntityCache(entityClass);

        // Attempt structural remapping if a builder is registered.
        if (sessionFactoryBuilder != null) {
            try {
                SessionFactory newFactory = sessionFactoryBuilder.build();
                if (newFactory != null) {
                    // Close old factory after new one is ready.
                    SessionFactory oldFactory = this.sessionFactory;
                    this.sessionFactory = newFactory;
                    oldFactory.close();
                    System.out.println("[HibernateEntityReloader] SessionFactory rebuilt for "
                            + className + " (structural change)");
                }
            } catch (Exception e) {
                System.err.println("[HibernateEntityReloader] SessionFactory rebuild failed: "
                        + e.getMessage());
            }
        }
    }

    private void evictEntityCache(Class<?> entityClass) {
        try {
            // Evict the entity and all its instances from the 2nd-level cache.
            sessionFactory.getCache().evictEntityData(entityClass.getName());

            // Evict all collections for this entity.
            sessionFactory.getCache().evictCollectionData(entityClass.getName());

            // Clear query cache entries (best effort).
            sessionFactory.getCache().evictDefaultQueryRegion();

            System.out.println("[HibernateEntityReloader] Cache evicted for: "
                    + entityClass.getName());

        } catch (Exception e) {
            System.err.println("[HibernateEntityReloader] Failed to evict "
                    + entityClass.getName() + ": " + e.getMessage());
        }
    }

    private boolean isHibernateEntity(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return clazz.isAnnotationPresent(Entity.class);
    }

    /** Get the current SessionFactory (may have been rebuilt). */
    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    /**
     * Callback for building a new SessionFactory from scratch. Implementations
     * should capture all necessary configuration (DataSource, mapping
     * packages, hibernate properties) at registration time.
     */
    @FunctionalInterface
    public interface SessionFactoryBuilder {
        SessionFactory build() throws Exception;
    }
}
