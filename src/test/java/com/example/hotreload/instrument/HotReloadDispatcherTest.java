package com.example.hotreload.instrument;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HotReloadDispatcher} — the runtime dispatch layer used by
 * bytecode-instrumented classes to route field access and method calls.
 */
class HotReloadDispatcherTest {

    static class Target {
        private String name = "initial";
        private int count = 0;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getCount() {
            return count;
        }

        public void increment() {
            count++;
        }

        public static String staticGreet(String who) {
            return "Hello, " + who;
        }
    }

    @Test
    void getFieldReturnsFieldValue() {
        Target target = new Target();
        Object value = HotReloadDispatcher.getField(target, Target.class.getName(), "name");
        assertEquals("initial", value);
    }

    @Test
    void setFieldUpdatesFieldValue() {
        Target target = new Target();
        HotReloadDispatcher.setField(target, Target.class.getName(), "name", "updated");
        assertEquals("updated", target.getName());
    }

    @Test
    void getFieldThrowsOnMissingField() {
        Target target = new Target();
        assertThrows(RuntimeException.class, () ->
                HotReloadDispatcher.getField(target, Target.class.getName(), "nonexistent"));
    }

    @Test
    void invokeCallsMethodOnInstance() {
        Target target = new Target();
        // Descriptor for getName(): ()Ljava/lang/String;
        Object result = HotReloadDispatcher.invoke(target, Target.class.getName(), "getName",
                "()Ljava/lang/String;");
        assertEquals("initial", result);
    }

    @Test
    void invokeWithArgumentsWorks() {
        Target target = new Target();
        // Descriptor for setName(String): (Ljava/lang/String;)V
        HotReloadDispatcher.invoke(target, Target.class.getName(), "setName",
                "(Ljava/lang/String;)V", "Bob");
        assertEquals("Bob", target.getName());
    }

    @Test
    void invokeStaticCallsStaticMethod() {
        Object result = HotReloadDispatcher.invokeStatic(Target.class, "staticGreet",
                "(Ljava/lang/String;)Ljava/lang/String;", "World");
        assertEquals("Hello, World", result);
    }

    @Test
    void invokeThrowsOnMissingMethod() {
        Target target = new Target();
        assertThrows(RuntimeException.class, () ->
                HotReloadDispatcher.invoke(target, Target.class.getName(), "doesNotExist",
                        "()V"));
    }
}
