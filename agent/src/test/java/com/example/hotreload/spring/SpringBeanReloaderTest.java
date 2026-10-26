package com.example.hotreload.spring;

import com.example.hotreload.HotReloadAgent;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SpringBeanReloader} — specifically the Spring stereotype
 * annotation detection logic.
 */
class SpringBeanReloaderTest {

    @Service
    static class MyService {
    }

    @Controller
    static class MyController {
    }

    @Repository
    static class MyRepository {
    }

    @Component
    static class MyComponent {
    }

    static class NotABean {
    }

    @Test
    void isSpringBeanRecognizesService() throws Exception {
        assertTrue(isSpringManagedBean(MyService.class));
    }

    @Test
    void isSpringBeanRecognizesController() throws Exception {
        assertTrue(isSpringManagedBean(MyController.class));
    }

    @Test
    void isSpringBeanRecognizesRepository() throws Exception {
        assertTrue(isSpringManagedBean(MyRepository.class));
    }

    @Test
    void isSpringBeanRecognizesComponent() throws Exception {
        assertTrue(isSpringManagedBean(MyComponent.class));
    }

    @Test
    void isSpringBeanRejectsPlainClass() throws Exception {
        assertFalse(isSpringManagedBean(NotABean.class));
    }

    @Test
    void isSpringBeanRejectsNull() throws Exception {
        assertFalse(isSpringManagedBean(null));
    }

    // isSpringBean is private — invoke via reflection.
    private boolean isSpringManagedBean(Class<?> clazz) throws Exception {
        Method method = SpringBeanReloader.class.getDeclaredMethod("isSpringManagedBean", Class.class);
        method.setAccessible(true);
        try {
            return (Boolean) method.invoke(new SpringBeanReloader(), clazz);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
