package com.example.hotreload.spring;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SpringContextHolder} — static holder for the running
 * Spring application context.
 */
class SpringContextHolderTest {

    @Test
    void registerAndGetContext() {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();

        try {
            SpringContextHolder.register(ctx);
            assertTrue(SpringContextHolder.isRegistered());
            assertSame(ctx, SpringContextHolder.getContext());
        } finally {
            SpringContextHolder.clear();
            ctx.close();
        }

        assertFalse(SpringContextHolder.isRegistered());
        assertNull(SpringContextHolder.getContext());
    }

    @Test
    void clearContext() {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();

        SpringContextHolder.register(ctx);
        assertTrue(SpringContextHolder.isRegistered());

        SpringContextHolder.clear();
        assertFalse(SpringContextHolder.isRegistered());
        assertNull(SpringContextHolder.getContext());

        ctx.close();
    }

    @Test
    void registerReplacesPrevious() {
        GenericApplicationContext ctx1 = new GenericApplicationContext();
        GenericApplicationContext ctx2 = new GenericApplicationContext();
        ctx1.refresh();
        ctx2.refresh();

        try {
            SpringContextHolder.register(ctx1);
            assertSame(ctx1, SpringContextHolder.getContext());

            SpringContextHolder.register(ctx2);
            assertSame(ctx2, SpringContextHolder.getContext());
        } finally {
            SpringContextHolder.clear();
            ctx1.close();
            ctx2.close();
        }
    }
}
