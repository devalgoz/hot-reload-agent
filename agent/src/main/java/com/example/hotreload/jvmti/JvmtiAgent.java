package com.example.hotreload.jvmti;

/**
 * JNI bridge to the native JVMTI agent.
 *
 * Provides heap iteration for full transparency:
 *   - getAllLoadedClasses(): find all classes across all classloaders
 *   - iterateAndMigrateInstances(): find all instances of a class for field migration
 *   - redefineClassStructural(): attempt JVMTI redefinition (same limits as Instrumentation)
 *
 * Loads libjvmti_agent.so (Linux) or jvmti_agent.dll (Windows).
 *
 * Build:
 *   gcc -shared -fPIC -I$JAVA_HOME/include -I$JAVA_HOME/include/linux \
 *       -o libjvmti_agent.so src/native/jvmti_agent.c
 */
public class JvmtiAgent {

    static {
        String libName = "jvmti_agent";
        try {
            System.loadLibrary(libName);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[JvmtiAgent] Could not load native library: " + libName);
        }
    }

    /** Native: redefine a single class via JVMTI */
    public static native int redefineClassStructural(Class<?> clazz, byte[] classBytes);

    /** Native: migrate fields from old instance to new instance */
    public static native int migrateInstanceFields(Object oldObj, Object newObj);

    /** Native: iterate over all instances of oldKlass, return count */
    public static native int iterateAndMigrateInstances(Class<?> oldKlass, Object newInstance);

    /** Native: get all loaded classes */
    public static native java.util.List<Class<?>> getAllClasses();

    /** Native: cleanup */
    public static native void destroy();

    public static boolean isNativeLoaded() {
        try {
            Class.forName("com.example.hotreload.jvmti.JvmtiAgent");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
