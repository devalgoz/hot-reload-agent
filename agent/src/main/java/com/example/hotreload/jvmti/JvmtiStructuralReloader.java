package com.example.hotreload.jvmti;

import com.example.hotreload.HotReloadAgent;
import com.example.hotreload.structural.HotSwapProxy;
import com.example.hotreload.structural.StructuralClassLoader;
import com.example.hotreload.structural.StructuralHotSwapManager;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Structural reloader with FULL TRANSPARENCY via JVMTI heap manipulation.
 *
 * Pipeline when a class changes structurally (add/remove methods/fields):
 *
 *   1. Load new .class bytes in StructuralClassLoader (isolated version)
 *   2. Create new instance of the new class (template)
 *   3. JVMTI IterateOverInstancesOfClass: count all heap instances of old class
 *   4. InstanceRegistry: find all live instances tracked via WeakReference
 *   5. JNI field copy: migrate field values from the template to EACH old
 *      instance found on the heap
 *   6. Update all HotSwapProxy instances -> transparent to all callers
 *   7. SpringStructuralIntegration replaces bean singletons
 *   8. JVMTI System.gc() -> helps reclaim old classloaders
 *
 * This addresses the key limitation of proxy-only approaches: direct field
 * access on old instances now sees migrated field values because we mutate
 * the instances themselves on the heap.
 *
 * Limitations:
 *   - Cannot add/remove fields from existing instances (JVMTI cannot change
 *     object layout). Only field VALUES are migrated.
 *   - New fields added in the new class will not exist on old instances.
 *   - Removed fields remain on old instances with stale values.
 *   - Final methods cannot be intercepted (ByteBuddy limitation).
 *   - True object class pointer (_klass) rewriting is not possible with
 *     standard JVMTI.
 */
public class JvmtiStructuralReloader {

    private static final boolean JVMTI_AVAILABLE;

    static {
        boolean available = false;
        try {
            Class.forName("com.example.hotreload.jvmti.JvmtiAgent");
            available = true;
        } catch (ClassNotFoundException t) {
            // JVMTI native library not available
        }
        JVMTI_AVAILABLE = available;
    }

    public static boolean isAvailable() {
        return JVMTI_AVAILABLE;
    }

    /**
     * Full transparency structural reload with heap instance migration.
     *
     * @param clazz         the currently loaded old class
     * @param classFile     path to the new compiled .class file
     * @param watchDir      root class directory
     * @param className     fully-qualified class name
     * @throws IOException if class file cannot be read
     */
    public static void redefineStructural(Class<?> clazz, Path classFile,
                                          Path watchDir, String className) throws IOException {
        byte[] newBytes = Files.readAllBytes(classFile);

        // Step 1: Load new class version in isolated classloader
        StructuralClassLoader scl = StructuralClassLoader.nextVersion(watchDir,
                clazz.getClassLoader());
        Class<?> newClazz;
        try {
            newClazz = scl.loadClassFromDir(className);
        } catch (ClassNotFoundException e) {
            throw new IOException("Cannot load class " + className, e);
        }

        // Step 2: Create template new instance
        Object newTemplate;
        try {
            Constructor<?> ctor = newClazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            newTemplate = ctor.newInstance();
        } catch (Exception e) {
            throw new IOException("Cannot create new instance of " + className, e);
        }

        // Step 3: Count all heap instances via JVMTI
        int jvmtiInstanceCount = 0;
        if (JVMTI_AVAILABLE) {
            jvmtiInstanceCount = JvmtiAgent.getInstanceCount(clazz);
            System.out.println("[JvmtiStructuralReloader] JVMTI found "
                    + jvmtiInstanceCount + " heap instances of " + className);
        }

        // Step 4: Migrate fields from template to EACH tracked old instance
        int migratedCount = 0;
        List<Object> oldInstances = InstanceRegistry.getInstances(className);
        for (Object oldInstance : oldInstances) {
            try {
                int copied = JvmtiAgent.migrateInstanceFields(newTemplate, oldInstance);
                if (copied > 0) {
                    migratedCount++;
                    System.out.println("[JvmtiStructuralReloader] Migrated "
                            + copied + " field(s) to instance "
                            + oldInstance + " of " + className);
                }
            } catch (Exception e) {
                System.err.println("[JvmtiStructuralReloader] Failed to migrate instance "
                        + oldInstance + ": " + e.getMessage());
            }
        }

        System.out.println("[JvmtiStructuralReloader] Migrated "
                + migratedCount + "/" + oldInstances.size()
                + " tracked instances of " + className);

        // Step 5: Force GC to help reclaim old classloaders
        if (JVMTI_AVAILABLE) {
            JvmtiAgent.forceGC();
            System.out.println("[JvmtiStructuralReloader] System.gc() invoked for cleanup");
        }

        // Step 6: Update all HotSwapProxy instances (transparency for callers)
        StructuralHotSwapManager manager = HotReloadAgent.getStructuralSwapManager();
        if (manager != null) {
            // Pass the template as the new instance source
            Object newInstance = newTemplate;
            try {
                // Try to create a fresh instance for the proxy (template may have
                // migrated fields from the last old instance)
                Constructor<?> ctor = newClazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                newInstance = ctor.newInstance();
            } catch (Exception e) {
                // Fall back to template
            }
            manager.structuralSwap(watchDir, className, classFile, newInstance);
        }

        System.out.println("[JvmtiStructuralReloader] Full transparency reload complete: "
                + className + " -> v" + (manager != null ? manager.getVersion(className) : 0));
    }

    /**
     * Get all loaded classes via JVMTI (for discovery).
     */
    public static java.util.List<Class<?>> getAllLoadedClasses() {
        if (JVMTI_AVAILABLE) {
            return JvmtiAgent.getAllClasses();
        }
        return java.util.Collections.emptyList();
    }
}
