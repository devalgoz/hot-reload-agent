package com.example.hotreload.hibernate;

import com.example.hotreload.HotReloadAgent;
import org.hibernate.SessionFactory;

/**
 * Hot-reloads Hibernate {@code @Entity} classes when their compiled bytecode
 * changes on disk.
 *
 * What this does:
 *   - Detects changed entity classes.
 *   - Evicts the entity (and its collections/queries) from the Hibernate
 *     SessionFactory's first- and second-level caches so stale instances are
 *     not returned.
 *
 * Limitations (JRebel does more here):
 *   - Hibernate builds its metamodel at SessionFactory startup. Adding new
 *     fields, associations, or changing mappings requires rebuilding the
 *     SessionFactory (full restart). This reloader handles *data* changes and
 *     cache eviction, not structural mapping changes.
 *   - Requires the SessionFactory to be injected via setSessionFactory().
 */
public class HibernateEntityReloader implements HotReloadAgent.ReloadListener {

    private volatile SessionFactory sessionFactory;

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
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

        System.out.println("[HibernateEntityReloader] Evicting entity cache: "
                + className);

        try {
            // Evict the entity and all its instances from the 2nd-level cache.
            sessionFactory.getCache().evictEntityData(entityClass.getName());

            // Evict all collections for this entity (role = entity name + "." + field).
            sessionFactory.getCache().evictCollectionData(entityClass.getName());

            // Clear query cache entries (best effort).
            sessionFactory.getCache().evictDefaultQueryRegion();

            System.out.println("[HibernateEntityReloader] Cache evicted for: "
                    + className);

        } catch (Exception e) {
            System.err.println("[HibernateEntityReloader] Failed to evict "
                    + className + ": " + e.getMessage());
        }
    }

    private boolean isHibernateEntity(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return clazz.isAnnotationPresent(jakarta.persistence.Entity.class);
    }
}
