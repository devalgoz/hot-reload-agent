package com.example.hotreload.remote;

import com.sun.tools.attach.VirtualMachine;

/**
 * Dynamically attaches the Hot-Reload Agent to a running JVM.
 *
 * Instead of starting the target application with {@code -javaagent}, this
 * client attaches the agent to an already-running JVM at runtime. This is the
 * programmatic foundation for remote server support: an operator can load the
 * agent into a production JVM without restarting it.
 *
 * Usage:
 *   java -cp hot-reload-agent.jar \
 *       com.example.hotreload.remote.RemoteAttachClient <pid> <agent-jar> [watch-dir]
 *
 * Examples:
 *   # Attach to a local JVM by PID
 *   java -cp target/hot-reload-agent-1.0.0-SNAPSHOT.jar \
 *       com.example.hotreload.remote.RemoteAttachClient 12345 \
 *       target/hot-reload-agent-1.0.0-SNAPSHOT.jar target/classes
 *
 *   # Attach and watch a custom directory
 *   java -cp target/hot-reload-agent-1.0.0-SNAPSHOT.jar \
 *       com.example.hotreload.remote.RemoteAttachClient 12345 \
 *       target/hot-reload-agent-1.0.0-SNAPSHOT.jar /opt/app/classes
 *
 * Limitations:
 *   - The target JVM must expose the Attach API (not disabled via
 *     -XX:+DisableAttachMechanism).
 *   - The agent jar must be on the client's classpath (or a filesystem path
 *     accessible to the target JVM).
 *   - This attaches locally; true remote attach (over SSH/HTTP) requires an
 *     additional agent-side listener, which is not implemented in this starter.
 */
public class RemoteAttachClient {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: RemoteAttachClient <pid> <agent-jar-path> [watch-dir]");
            System.err.println("  pid          - Target JVM process ID");
            System.err.println("  agent-jar    - Path to hot-reload-agent jar");
            System.err.println("  watch-dir    - Directory the agent watches for .class changes (default: target/classes)");
            System.exit(1);
        }

        String pid = args[0];
        String agentJar = args[1];
        String watchDir = (args.length > 2 && !args[2].isBlank()) ? args[2] : "target/classes";

        System.out.println("[RemoteAttachClient] Attaching to JVM " + pid + "...");
        VirtualMachine vm = VirtualMachine.attach(pid);

        try {
            System.out.println("[RemoteAttachClient] Connected. Loading agent " + agentJar
                    + " with watch-dir=" + watchDir);
            vm.loadAgent(agentJar, watchDir);
            System.out.println("[RemoteAttachClient] Agent loaded successfully into JVM " + pid);
        } finally {
            vm.detach();
            System.out.println("[RemoteAttachClient] Detached from JVM " + pid);
        }
    }
}
