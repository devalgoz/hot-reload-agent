package com.example.hotreload.structural;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HotSwapProxy} — the JDK dynamic proxy used for
 * transparent hot-swapping of interface-based instances.
 */
class HotSwapProxyTest {

    interface Greeter {
        String greet(String name);
    }

    static class GreeterImpl implements Greeter {
        private String prefix = "Hello";

        @Override
        public String greet(String name) {
            return prefix + ", " + name;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }
    }

    @Test
    void proxyDelegatesToCurrentInstance() {
        GreeterImpl impl = new GreeterImpl();
        HotSwapProxy proxy = HotSwapProxy.create(impl, Greeter.class);
        Greeter greeter = (Greeter) proxy.getProxy();

        assertNotNull(greeter);
        assertEquals("Hello, World", greeter.greet("World"));
    }

    @Test
    void setCurrentInstanceChangesTarget() {
        GreeterImpl impl1 = new GreeterImpl();
        impl1.setPrefix("Hello");

        GreeterImpl impl2 = new GreeterImpl();
        impl2.setPrefix("Hola");

        HotSwapProxy proxy = HotSwapProxy.create(impl1, Greeter.class);
        Greeter greeter = (Greeter) proxy.getProxy();

        assertEquals("Hello, World", greeter.greet("World"));

        proxy.setCurrentInstance(impl2);
        assertEquals("Hola, World", greeter.greet("World"));
    }

    @Test
    void getCurrentInstanceReturnsCurrentTarget() {
        GreeterImpl impl = new GreeterImpl();
        HotSwapProxy proxy = HotSwapProxy.create(impl, Greeter.class);
        assertSame(impl, proxy.getCurrentInstance());
    }

    @Test
    void proxyImplementsRequestedInterfaces() {
        HotSwapProxy proxy = HotSwapProxy.create(new Object(), Greeter.class);
        Object proxyObj = proxy.getProxy();
        assertTrue(Proxy.isProxyClass(proxyObj.getClass()));
        assertTrue(Greeter.class.isAssignableFrom(proxyObj.getClass()));
    }

    @Test
    void invokeWithNullInstanceThrows() throws Exception {
        // The constructor is private; use reflection to create with null target.
        java.lang.reflect.Constructor<HotSwapProxy> ctor =
                HotSwapProxy.class.getDeclaredConstructor(Object.class, boolean.class);
        ctor.setAccessible(true);
        HotSwapProxy proxy = ctor.newInstance((Object) null, false);
        // invoke() declares throws Throwable, so the lambda can throw directly.
        assertThrows(IllegalStateException.class, () ->
                proxy.invoke(null, Greeter.class.getMethod("greet", String.class),
                        new Object[]{"x"}));
    }
}
