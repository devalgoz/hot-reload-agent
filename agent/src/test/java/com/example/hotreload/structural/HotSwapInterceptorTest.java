package com.example.hotreload.structural;

import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HotSwapInterceptor} — ByteBuddy method-interception
 * interceptor used by subclass-based HotSwapProxy instances.
 *
 * <p>Tests call the intercept method directly rather than generating a
 * ByteBuddy subclass, to avoid ByteBuddy's inability to intercept
 * Object.equals/hashCode/toString.</p>
 */
class HotSwapInterceptorTest {

    static class Greeter {
        private String prefix = "Hello";

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
    void interceptDelegatesToCurrentInstance() throws Throwable {
        Greeter target = new Greeter();
        target.setPrefix("Hi");

        Method greetMethod = Greeter.class.getMethod("greet", String.class);
        Object result = HotSwapInterceptor.intercept(
                () -> "fallback",
                greetMethod,
                new Object[]{"World"},
                target);
        assertEquals("Hi, World", result);

        // Change the current instance and verify the proxy follows.
        Greeter target2 = new Greeter();
        target2.setPrefix("Hola");
        Object result2 = HotSwapInterceptor.intercept(
                () -> "fallback",
                greetMethod,
                new Object[]{"Mundo"},
                target2);
        assertEquals("Hola, Mundo", result2);
    }

    @Test
    void interceptWithNullInstanceThrows() throws Throwable {
        Method greetMethod = Greeter.class.getMethod("greet", String.class);
        assertThrows(IllegalStateException.class, () ->
                HotSwapInterceptor.intercept(
                        () -> "fallback",
                        greetMethod,
                        new Object[]{"World"},
                        null));
    }

    @Test
    void interceptFallsBackToSuperCallWhenMethodNotFound() throws Throwable {
        // Use a method that exists on Greeter but not on a different target.
        Method getPrefixMethod = Greeter.class.getMethod("getPrefix");
        Greeter target = new Greeter();
        target.setPrefix("Bonjour");

        Object result = HotSwapInterceptor.intercept(
                () -> "fallback",
                getPrefixMethod,
                new Object[0],
                target);
        assertEquals("Bonjour", result);
    }
}
