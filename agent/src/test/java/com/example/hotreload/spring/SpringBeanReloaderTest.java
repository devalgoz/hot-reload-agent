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
        assertTrue(isSpringBean(MyService.class));
    }

    @Test
    void isSpringBeanRecognizesController() throws Exception {
        assertTrue(isSpringBean(MyController.class));
    }

    @Test
    void isSpringBeanRecognizesRepository() throws Exception {
        assertTrue(isSpringBean(MyRepository.class));
    }

    @Test
    void isSpringBeanRecognizesComponent() throws Exception {
        assertTrue(isSpringBean(MyComponent.class));
    }

    @Test
    void isSpringBeanRejectsPlainClass() throws Exception {
        assertFalse(isSpringBean(NotABean.class));
    }

    @Test
    void isSpringBeanRejectsNull() throws Exception {
        assertFalse(isSpringBean(null));
    }

    // isSpringBean is private — invoke via reflection.
    private boolean isSpringBean(Class<?> clazz) throws Exception {
        Method method = SpringBeanReloader.class.getDeclaredMethod("isSpringBean", Class.class);
        method.setAccessible(true);
        try {
            return (Boolean) method.invoke(new SpringBeanReloader(), clazz);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
