package com.example.hotreload.jvmti;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live instances of classes for JVMTI heap manipulation.
 *
 * <p>When {@link #trackInstance(Object)} is called, the instance is wrapped
 * in a {@link WeakReference} and stored under its class name. This allows
 * the hot-reload agent to find all heap instances of a class and migrate
 * their fields when a structural change occurs — even for code holding
 * direct references (bypassing the HotSwapProxy).</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   // In SpringStructuralIntegration or similar:
 *   InstanceRegistry.trackInstance(bean);
 *
 *   // During structural reload:
 *   List<Object> instances = InstanceRegistry.getInstances(className);
 *   for (Object oldInstance : instances) {
 *       JvmtiAgent.migrateInstanceFields(oldInstance, newInstance);
 *   }
 * </pre>
 *
 * <p>WeakReferences ensure tracked instances are garbage-collected normally
 * when no longer referenced, preventing memory leaks.</p>
 */
public class InstanceRegistry {

    /** className -> list of weak references to instances */
    private static final Map<String, List<WeakReference<Object>>> registry =
            new ConcurrentHashMap<>();

    private InstanceRegistry() {
    }

    /**
     * Track an instance so it can be found later for field migration.
     *
     * @param instance the object to track
     */
    public static void trackInstance(Object instance) {
        if (instance == null) {
            return;
        }
        String className = instance.getClass().getName();
        registry.computeIfAbsent(className, k -> new ArrayList<>())
                .add(new WeakReference<>(instance));
    }

    /**
     * Get all live instances of a class. Stale weak references are removed.
     *
     * @param className fully-qualified class name
     * @return list of live instances (may be empty)
     */
    public static List<Object> getInstances(String className) {
        List<Object> result = new ArrayList<>();
        List<WeakReference<Object>> refs = registry.get(className);
        if (refs == null) {
            return result;
        }
        // Clean up stale references and collect live ones.
        refs.removeIf(ref -> {
            Object obj = ref.get();
            if (obj != null) {
                result.add(obj);
                return false; // keep live reference
            }
            return true; // remove stale reference
        });
        return result;
    }

    /**
     * Get the number of live tracked instances of a class.
     *
     * @param className fully-qualified class name
     * @count of live instances
     */
    public static int instanceCount(String className) {
        return getInstances(className).size();
    }

    /**
     * Clear all tracked references for a class (e.g. after classloader cleanup).
     *
     * @param className fully-qualified class name
     */
    public static void clear(String className) {
        registry.remove(className);
    }

    /** Clear all tracked references (e.g. during shutdown). */
    public static void clearAll() {
        registry.clear();
    }

    /** Total number of classes being tracked. */
    public static int trackedClassCount() {
        return registry.size();
    }
}
