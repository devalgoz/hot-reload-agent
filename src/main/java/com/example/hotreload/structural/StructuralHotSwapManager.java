package com.example.hotreload.structural;

import com.example.hotreload.HotReloadAgent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates structural hot-swapping with transparency via indirection.
 *
 * Since java.lang.instrument cannot rewrite heap references (that requires
 * native JVMTI), we use the {@link HotSwapProxy} indirection pattern:
 *
 *   - Instead of holding a direct reference to a bean, clients hold a proxy.
 *   - The proxy forwards all method calls to {@code currentInstance}.
 *   - On structural swap, we update the proxy's currentInstance to the new
 *     version. All existing proxy holders transparently see the new version.
 *
 * Flow:
 *   1. Detect structural change (redefineClasses throws).
 *   2. Load new .class bytes in StructuralClassLoader (isolated version).
 *   3. Create new instance, migrate fields from old.
 *   4. Update all registered HotSwapProxy instances to point to new instance.
 *   5. Notify Spring to replace bean singleton with the proxy.
 *
 * Limitations:
 *   - Only interface-based beans can be proxied (JDK dynamic proxy).
 *   - Direct field access on the target object bypasses the proxy.
 *   - Identity checks (==) compare the proxy, not the underlying instance.
 *   - True heap-level transparency requires native JVMTI (JRebel's approach).
 */
public class StructuralHotSwapManager implements HotReloadAgent.ReloadListener {

    /** Tracks className -> current structural version number. */
    private final Map<String, Integer> classVersions = new ConcurrentHashMap<>();

    /** Tracks beanName -> HotSwapProxy for transparent updates. */
    private final Map<String, HotSwapProxy> proxyRegistry = new ConcurrentHashMap<>();

    /** Tracks className -> old instance (for field migration). */
    private final Map<String, Object> oldInstances = new ConcurrentHashMap<>();

    /** Listeners notified when a structural swap completes. */
    private final List<StructuralSwapListener> listeners = new ArrayList<>();

    /** Class directory being watched (e.g. target/classes). */
    private Path classDir;

    public void setClassDir(Path classDir) {
        this.classDir = classDir;
    }

    public void addListener(StructuralSwapListener listener) {
        listeners.add(listener);
    }

    @Override
    public void onClassReloaded(String className, byte[] newBytes) {
        // This listener is invoked for ALL class changes. Non-structural
        // changes are handled by HotReloadAgent.redefineClass before this
        // listener is called. Structural changes go through structuralSwap()
        // which is called directly by HotReloadAgent.reloadClass().
    }

    /**
     * Register a bean (wrapped in a HotSwapProxy) so that future structural
     * swaps transparently update all holders of the proxy.
     *
     * @param beanName   Spring bean name
     * @param proxy      HotSwapProxy wrapping the bean instance
     * @param className  fully-qualified class name of the bean
     */
    public void registerProxy(String beanName, HotSwapProxy proxy, String className) {
        proxyRegistry.put(beanName, proxy);
        oldInstances.put(className, proxy.getCurrentInstance());
        System.out.println("[StructuralHotSwapManager] Registered proxy for bean: "
                + beanName + " (" + className + ")");
    }

    /**
     * Perform a structural hot-swap for the given class, then update all
     * registered proxies so existing holders transparently see the new version.
     *
     * @param watchDir   root directory containing compiled .class files
     * @param className   fully-qualified class name (dot notation)
     * @param classFile   path to the new compiled .class file
     * @param oldInstance the existing instance (from old classloader), or null
     * @return the new instance loaded in the StructuralClassLoader
     */
    public Object structuralSwap(Path watchDir, String className, Path classFile, Object oldInstance) {
        try {
            // Remember the old instance for field migration.
            if (oldInstance != null) {
                oldInstances.put(className, oldInstance);
            }

            Path effectiveClassDir = watchDir;

            // Determine the next structural version.
            Integer currentVersion = classVersions.get(className);
            int newVersion = (currentVersion == null ? 0 : currentVersion) + 1;
            classVersions.put(className, newVersion);

            // Create a new StructuralClassLoader for this version.
            ClassLoader parent = Thread.currentThread().getContextClassLoader();
            if (parent == null) {
                parent = StructuralClassLoader.class.getClassLoader();
            }
            StructuralClassLoader scl = StructuralClassLoader.nextVersion(effectiveClassDir, parent);

            // Load the new class version from disk.
            Class<?> newClazz = scl.loadClassFromDir(className);
            System.out.println("[StructuralHotSwapManager] Loaded v" + newVersion
                    + " of " + className + " in " + scl.getClass().getSimpleName());

            // Create a new instance.
            Object newInstance = newClazz.getDeclaredConstructor().newInstance();

            // Migrate fields from old instance to new instance (best effort).
            if (oldInstance != null) {
                migrateFields(oldInstance, newInstance);
            }

            // --- TRANSPARENCY: Update all registered proxies for this bean ---
            int proxiesUpdated = 0;
            for (Map.Entry<String, HotSwapProxy> entry : proxyRegistry.entrySet()) {
                String beanName = entry.getKey();
                HotSwapProxy proxy = entry.getValue();
                Object current = proxy.getCurrentInstance();
                // If this proxy wraps an instance of the old class, update it.
                if (current != null && current.getClass().getName().equals(className)) {
                    proxy.setCurrentInstance(newInstance);
                    proxiesUpdated++;
                    System.out.println("[StructuralHotSwapManager] Updated proxy for bean: "
                            + beanName + " -> v" + newVersion);
                }
            }

            // Notify listeners (Spring, etc.) to replace the bean singleton.
            for (StructuralSwapListener listener : listeners) {
                try {
                    listener.onStructuralSwapped(className, newInstance, newClazz, scl);
                } catch (Exception e) {
                    System.err.println("[StructuralHotSwapManager] Listener error: "
                            + e.getMessage());
                }
            }

            System.out.println("[StructuralHotSwapManager] Structural swap complete for "
                    + className + " v" + newVersion + " (proxies updated: " + proxiesUpdated + ")");
            return newInstance;

        } catch (Exception e) {
            System.err.println("[StructuralHotSwapManager] Structural swap failed for "
                    + className + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Copy field values from old instance to new instance. Only copies fields
     * that exist in both classes with the same name and type.
     */
    private void migrateFields(Object oldInstance, Object newInstance) {
        Class<?> oldClass = oldInstance.getClass();
        Class<?> newClass = newInstance.getClass();

        for (Field oldField : oldClass.getDeclaredFields()) {
            try {
                oldField.setAccessible(true);
                Field newField = newClass.getDeclaredField(oldField.getName());
                newField.setAccessible(true);

                if (newField.getType().isAssignableFrom(oldField.getType())) {
                    Object value = oldField.get(oldInstance);
                    newField.set(newInstance, value);
                    System.out.println("[StructuralHotSwapManager] Migrated field: "
                            + oldField.getName());
                }
            } catch (NoSuchFieldException e) {
                // Field doesn't exist in new class — skip (it may be new).
            } catch (Exception e) {
                System.err.println("[StructuralHotSwapManager] Field migration warning: "
                        + e.getMessage());
            }
        }
    }

    /** Get the current structural version of a class. */
    public int getVersion(String className) {
        Integer v = classVersions.get(className);
        return v == null ? 0 : v;
    }

    /** Get all registered proxies (for debugging / Spring integration). */
    public Map<String, HotSwapProxy> getProxyRegistry() {
        return proxyRegistry;
    }

    /** Listener notified when a structural swap completes. */
    @FunctionalInterface
    public interface StructuralSwapListener {
        void onStructuralSwapped(String className, Object newInstance,
                                 Class<?> newClazz, StructuralClassLoader classLoader);
    }
}
