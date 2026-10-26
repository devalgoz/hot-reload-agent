package com.example.hotreload.spring;

import com.example.hotreload.HotReloadAgent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for expanded bean detection in {@link SpringBeanReloader}.
 */
class SpringBeanReloaderExpandedTest {

    @Test
    void isSpringManagedBeanRecognizesRegisteredBeanType() throws Exception {
        SpringBeanReloader reloader = new SpringBeanReloader();
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();

        try {
            // Register a bean type without stereotype annotations.
            ctx.getBeanFactory().registerSingleton("myBean", new Object());
            // Set context via reflection since setContext is public.
            Method setContext = SpringBeanReloader.class.getDeclaredMethod("setContext", ConfigurableApplicationContext.class);
            setContext.setAccessible(true);
            setContext.invoke(reloader, ctx);

            Method isSpringManagedBean = SpringBeanReloader.class.getDeclaredMethod("isSpringManagedBean", Class.class);
            isSpringManagedBean.setAccessible(true);

            assertTrue((Boolean) isSpringManagedBean.invoke(reloader, Object.class));
        } finally {
            ctx.close();
        }
    }

    @Test
    void isSpringManagedBeanRejectsUnregisteredPlainClass() throws Exception {
        SpringBeanReloader reloader = new SpringBeanReloader();
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();

        try {
            Method setContext = SpringBeanReloader.class.getDeclaredMethod("setContext", ConfigurableApplicationContext.class);
            setContext.setAccessible(true);
            setContext.invoke(reloader, ctx);

            Method isSpringManagedBean = SpringBeanReloader.class.getDeclaredMethod("isSpringManagedBean", Class.class);
            isSpringManagedBean.setAccessible(true);

            // Unregistered plain class — not a bean.
            assertFalse((Boolean) isSpringManagedBean.invoke(reloader, String.class));
        } finally {
            ctx.close();
        }
    }

    @Test
    void isSpringManagedBeanRejectsNull() throws Exception {
        SpringBeanReloader reloader = new SpringBeanReloader();
        Method isSpringManagedBean = SpringBeanReloader.class.getDeclaredMethod("isSpringManagedBean", Class.class);
        isSpringManagedBean.setAccessible(true);
        assertFalse((Boolean) isSpringManagedBean.invoke(reloader, (Class<?>) null));
    }
}
