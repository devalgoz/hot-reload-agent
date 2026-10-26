package com.example.hotreload;

import com.example.hotreload.jvmti.JvmtiStructuralReloader;
import com.example.hotreload.structural.StructuralClassLoader;
import com.example.hotreload.structural.StructuralHotSwapManager;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

/**
 * Entry point for the hot-reload Java agent with FULL TRANSPARENCY.
 *
 * Supports two attach modes:
 *  1. Startup agent:  java -javaagent:hot-reload-agent.jar [...]
 *  2. Dynamic attach: VirtualMachine.attach(...)->agent.load(agentJar, args)
 *
 * Reload strategy:
 *   1. Try {@link Instrumentation#redefineClasses} to swap method bodies of
 *      already-loaded classes with freshly compiled bytes from disk.
 *   2. For structural changes (add/remove methods/fields):
 *      a. Load new class version in {@link StructuralClassLoader} (isolated)
 *      b. Create new instance, migrate fields from old instances via JVMTI
 *      c. Update all {@link HotSwapProxy} instances (transparent to callers)
 *      d. Notify Spring to replace bean singletons
 *
 * NOTE: JVMTI RedefineClasses has the SAME limitation as java.lang.instrument
 * — it cannot add or remove methods/fields in standard HotSpot/OpenJDK builds.
 * The custom classloader approach is the only way to handle structural changes.
 */
public class HotReloadAgent {

    private static volatile Instrumentation instrumentation;
    private static ReloadService reloadService;
    private static HotReloadClassFileTransformer classFileTransformer;
    private static StructuralHotSwapManager structuralSwapManager;

    /** Called by the JVM when the agent is started with -javaagent. */
    public static void premain(String agentArgs, Instrumentation inst) {
        bootstrap(inst, agentArgs);
    }

    /** Called when the agent is dynamically attached to a running JVM. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        bootstrap(inst, agentArgs);
    }

    private static void bootstrap(Instrumentation inst, String agentArgs) {
        instrumentation = inst;

        // Allow redefinition/retransformation of loaded classes at runtime.
        classFileTransformer = new HotReloadClassFileTransformer();
        inst.addTransformer(classFileTransformer, true);

        // Install the ByteBuddy-based bytecode transformer (method tracing, etc.).
        ByteBuddyAgentTransformer.install(inst);

        // Start the file watcher that triggers reloads when .class files change.
        try {
            reloadService = new ReloadService();
        } catch (IOException e) {
            System.err.println("[HotReloadAgent] Could not create reload watcher: "
                    + e.getMessage());
            reloadService = null;
        }
        if (reloadService != null) {
            reloadService.start(agentArgs);
        }

        // Initialize the structural hot-swap manager (custom classloader +
        // proxy indirection for add/remove methods/fields).
        structuralSwapManager = new StructuralHotSwapManager();

        System.out.println("[HotReloadAgent] Agent active. Instrumentation ready.");
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    /**
     * Retransform a loaded class using the registered transformer.
     * Useful for load-time bytecode weaving scenarios.
     */
    public static void retransformClass(Class<?> clazz) throws Exception {
        if (instrumentation == null) {
            throw new IllegalStateException("Agent not initialized");
        }
        instrumentation.retransformClasses(clazz);
    }

    /**
     * Redefine a loaded class with fresh byte[] read from disk.
     * Swaps method bodies of the already-loaded class — no restart required.
     * Cannot add/remove methods or fields (JVM limitation).
     */
    public static void redefineClass(Class<?> clazz, byte[] newClassBytes) throws Exception {
        if (instrumentation == null) {
            throw new IllegalStateException("Agent not initialized");
        }
        instrumentation.redefineClasses(
                new java.lang.instrument.ClassDefinition(clazz, newClassBytes));
    }

    /**
     * Convenience: find a loaded class by name and reload it with FULL
     * TRANSPARENCY via JVMTI heap iteration + StructuralClassLoader.
     *
     * Pipeline:
     *   1. Try redefineClass (method-body changes only — fast, works on all JVMs)
     *   2. If structural change (add/remove method/field):
     *      a. Load new class version in StructuralClassLoader (isolated version)
 *      b. Create new instance of new class
 *      c. JVMTI IterateOverInstancesOfClass: find ALL old heap instances
 *      d. JNI field copy: migrate field values from old to new instances
 *      e. Update all HotSwapProxy instances (transparent to callers)
 *      f. SpringStructuralIntegration replaces bean singletons
     *
     * NOTE: JVMTI RedefineClasses has the SAME limitation as
     * java.lang.instrument — it cannot add or remove methods/fields in
     * standard HotSpot/OpenJDK builds. The custom classloader approach is
     * the only way to handle structural changes.
     *
     * Safe to call if the class isn't loaded yet — it's a no-op.
     *
     * Limitations:
     *   - Never redefines JDK or agent classes.
     *   - Full transparency requires JVMTI native agent loaded via -agentpath.
     *   - Without JVMTI, proxy indirection is used (callers see new version
     *     through proxy, but direct object references remain old).
     */
    public static void reloadClass(String className, Path watchDir, Path classFile, byte[] newClassBytes) {
        if (instrumentation == null) {
            return;
        }
        // Never redefine JDK / agent classes.
        if (className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("jdk.") ||
            className.startsWith("com.example.hotreload")) {
            return;
        }
        Class<?> clazz = findLoadedClass(className);
        if (clazz == null) {
            // Not loaded yet; nothing to redefine. It will be picked up on next load.
            return;
        }

        // Try redefineClass (method-body changes only)
        try {
            redefineClass(clazz, newClassBytes);
            System.out.println("[HotReloadAgent] Redefined class: " + className);
            return;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            boolean isStructural = msg.contains("attempted to add a method")
                || msg.contains("attempted to add or remove")
                || msg.contains("attempted to change")
                || msg.contains("attempted to set or change")
                || e instanceof java.lang.instrument.UnmodifiableClassException;

            if (isStructural && structuralSwapManager != null && watchDir != null) {
                // Full transparency structural reload via JVMTI + StructuralClassLoader
                System.out.println("[HotReloadAgent] Structural change detected for "
                        + className + " (" + e.getClass().getSimpleName() + "), "
                        + "using JVMTI + StructuralClassLoader + HotSwapProxy...");
                try {
                    JvmtiStructuralReloader.redefineStructural(clazz, classFile, watchDir, className);
                } catch (Exception ex) {
                    System.err.println("[HotReloadAgent] Structural swap failed for "
                            + className + ": " + ex.getMessage());
                }
            } else {
                System.err.println("[HotReloadAgent] Failed to redefine " + className
                        + ": " + e.getMessage());
            }
        }
    }

    /**
     * Find a loaded class by its fully-qualified name (dot notation).
     */
    public static Class<?> findLoadedClass(String className) {
        if (instrumentation == null) {
            return null;
        }
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }

    /** Register a reload listener (Spring, Hibernate, custom). */
    public static void addReloadListener(ReloadListener listener) {
        if (reloadService != null) {
            reloadService.addListener(listener);
        }
    }

    /**
     * Callback interface so Spring / Hibernate modules can react to specific
     * class changes.
     */
    @FunctionalInterface
    public interface ReloadListener {
        void onClassReloaded(String className, byte[] newBytes);
    }

    /** Get the structural hot-swap manager (for Spring/Hibernate integration). */
    public static StructuralHotSwapManager getStructuralSwapManager() {
        return structuralSwapManager;
    }
}
