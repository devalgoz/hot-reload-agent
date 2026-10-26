package com.example.hotreload.remote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RemoteAttachServer} lifecycle.
 *
 * <p>These tests do not actually attach an agent (requires a live JVM with
 * Attach API). They verify server start/stop and status endpoints.</p>
 */
class RemoteAttachServerTest {

    @Test
    void startAndStop() throws Exception {
        RemoteAttachServer server = new RemoteAttachServer(0); // port 0 = random
        server.start();
        assertTrue(server.isRunning());
        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    void agentAttachedFlag() {
        RemoteAttachServer server = new RemoteAttachServer(8099);
        assertFalse(server.isAgentAttached());

        server.setAgentAttached(true);
        assertTrue(server.isAgentAttached());
    }
}
