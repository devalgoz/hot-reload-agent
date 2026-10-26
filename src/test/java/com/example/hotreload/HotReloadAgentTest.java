package com.example.hotreload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HotReloadAgent} — the JVM agent entry point.
 *
 * These tests exercise the agent's state and the class-loading helpers that
 * do not require a running JVM with the agent attached.
 */
class HotReloadAgentTest {

    @Test
    void instrumentationIsNullBeforeAgentStarts() {
        // No -javaagent / agentmain has run in this test JVM, so the static
        // Instrumentation field should remain null.
        assertNull(HotReloadAgent.getInstrumentation());
    }

    @Test
    void findLoadedClassReturnsNullWhenInstrumentationIsNull() {
        assertNull(HotReloadAgent.findLoadedClass("java.lang.String"));
    }

    @Test
    void reloadClassIsNoOpWhenInstrumentationIsNull() {
        // Should not throw even though the agent is not initialized.
        assertDoesNotThrow(() -> HotReloadAgent.reloadClass(
                "com.example.Foo",
                java.nio.file.Paths.get("target/classes"),
                null,
                new byte[0]));
    }

    @Test
    void structuralSwapManagerIsNullBeforeAgentStarts() {
        assertNull(HotReloadAgent.getStructuralSwapManager());
    }
}
