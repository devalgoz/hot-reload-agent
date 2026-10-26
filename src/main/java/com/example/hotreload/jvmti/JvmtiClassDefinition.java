package com.example.hotreload.jvmti;

/**
 * Data class passed to the native JVMTI agent for class redefinition.
 * Contains a Class reference and its new bytecode.
 */
public class JvmtiClassDefinition {
    public final Class<?> klass;
    public final byte[] classData;

    public JvmtiClassDefinition(Class<?> klass, byte[] classData) {
        this.klass = klass;
        this.classData = classData;
    }
}
