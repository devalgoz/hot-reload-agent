package com.example.hotreload.hibernate;

import com.example.hotreload.HotReloadAgent;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HibernateEntityReloader} — specifically the Hibernate
 * {@link jakarta.persistence.Entity} annotation detection logic.
 */
class HibernateEntityReloaderTest {

    @Entity
    static class MyEntity {
    }

    static class NotAnEntity {
    }

    @Test
    void isHibernateEntityRecognizesEntity() throws Exception {
        assertTrue(isHibernateEntity(MyEntity.class));
    }

    @Test
    void isHibernateEntityRejectsPlainClass() throws Exception {
        assertFalse(isHibernateEntity(NotAnEntity.class));
    }

    @Test
    void isHibernateEntityRejectsNull() throws Exception {
        assertFalse(isHibernateEntity(null));
    }

    // isHibernateEntity is private — invoke via reflection.
    private boolean isHibernateEntity(Class<?> clazz) throws Exception {
        Method method = HibernateEntityReloader.class.getDeclaredMethod("isHibernateEntity", Class.class);
        method.setAccessible(true);
        try {
            return (Boolean) method.invoke(new HibernateEntityReloader(), clazz);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
