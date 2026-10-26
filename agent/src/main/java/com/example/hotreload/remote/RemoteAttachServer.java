package com.example.hotreload.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple HTTP server that allows remote attachment of the Hot-Reload Agent
 * to a running JVM.
 *
 * <p>Start the server in your application (or as a standalone process):</p>
 * <pre>
 *   RemoteAttachServer server = new RemoteAttachServer(8080);
 *   server.start();
 * </pre>
 *
 * <p>Then attach the agent via HTTP POST:</p>
 * <pre>
 *   curl -X POST http://localhost:8080/attach \
 *     -H "Content-Type: application/json" \
 *     -d '{"agentJar":"/path/to/agent.jar","watchDir":"/path/to/classes"}'
 * </pre>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /attach} — attach the agent to this JVM.</li>
 *   <li>{@code GET /status} — check if the agent is attached.</li>
 *   <li>{@code POST /stop} — stop the HTTP server.</li>
 * </ul>
 *
 * <p>Security note: This server has no authentication. In production, place it
 * behind a firewall, bind to localhost, or add authentication middleware.</p>
 */
public class RemoteAttachServer {

    private final int port;
    private HttpServer server;
    private volatile boolean running = false;

    /** Track whether the agent has been attached (for /status). */
    private volatile boolean agentAttached = false;

    public RemoteAttachServer(int port) {
        this.port = port;
    }

    public RemoteAttachServer(int port, boolean agentAttached) {
        this.port = port;
        this.agentAttached = agentAttached;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/attach", new AttachHandler());
        server.createContext("/status", new StatusHandler());
        server.createContext("/stop", new StopHandler());
        server.setExecutor(null); // default executor
        server.start();
        running = true;
        System.out.println("[RemoteAttachServer] HTTP attach server started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            running = false;
            System.out.println("[RemoteAttachServer] HTTP attach server stopped.");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void setAgentAttached(boolean attached) {
        this.agentAttached = attached;
    }

    public boolean isAgentAttached() {
        return agentAttached;
    }

    /** Handles POST /attach — loads the agent into this JVM via Attach API. */
    private class AttachHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed. Use POST.");
                return;
            }

            try {
                int length = exchange.getRequestBody().available();
                byte[] body = new byte[length];
                exchange.getRequestBody().read(body);
                String json = new String(body, StandardCharsets.UTF_8);

                // Simple JSON parsing (no external dependency).
                String agentJar = extractJsonField(json, "agentJar");
                String watchDir = extractJsonField(json, "watchDir");
                if (watchDir == null || watchDir.isEmpty()) {
                    watchDir = "target/classes";
                }

                if (agentJar == null || agentJar.isEmpty()) {
                    sendResponse(exchange, 400, "Missing 'agentJar' field.");
                    return;
                }

                // Attach the agent to this JVM.
                java.lang.management.ManagementFactory.getRuntimeMXBean()
                        .getName();
                String pid = java.lang.management.ManagementFactory.getRuntimeMXBean()
                        .getName().split("@")[0];

                System.out.println("[RemoteAttachServer] Attaching agent to local JVM PID " + pid);

                com.sun.tools.attach.VirtualMachine vm =
                        com.sun.tools.attach.VirtualMachine.attach(pid);
                try {
                    vm.loadAgent(agentJar, watchDir);
                    agentAttached = true;
                    sendResponse(exchange, 200,
                            "{\"status\":\"attached\",\"pid\":\"" + pid + "\"}");
                } finally {
                    vm.detach();
                }

            } catch (Exception e) {
                System.err.println("[RemoteAttachServer] Attach failed: " + e.getMessage());
                sendResponse(exchange, 500, "Attach failed: " + e.getMessage());
            }
        }
    }

    /** Handles GET /status — returns attach status. */
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String json = "{\"agentAttached\":" + agentAttached + ",\"port\":" + port + "}";
            sendResponse(exchange, 200, json);
        }
    }

    /** Handles POST /stop — stops the server. */
    private class StopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 200, "{\"status\":\"stopping\"}");
                stop();
            } else {
                sendResponse(exchange, 405, "Method not allowed. Use POST.");
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int code, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Extract a field value from simple JSON (no external dependency).
     */
    private static String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) {
            return null;
        }
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) {
            return null;
        }
        int start = json.indexOf('"', colonIdx + 1);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return json.substring(start + 1, end);
    }

    /** All known remote attach server instances (for shutdown hooks). */
    private static final Map<Integer, RemoteAttachServer> instances = new ConcurrentHashMap<>();

    public static RemoteAttachServer startOnPort(int port) throws IOException {
        RemoteAttachServer server = new RemoteAttachServer(port);
        server.start();
        instances.put(port, server);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop,
                "remote-attach-server-shutdown-" + port));
        return server;
    }
}
