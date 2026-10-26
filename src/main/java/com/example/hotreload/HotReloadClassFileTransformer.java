package com.example.hotreload;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * ClassFileTransformer hook for load-time bytecode modification.
 *
 * Returning {@code null} means "no transformation" — the class loads as-is.
 *
 * Wire ByteBuddy or ASM here to modify bytes when classes are loaded.
 * Currently returns null for all classes; the reload-from-disk path uses
 * {@link HotReloadAgent#redefineClass} instead of this transformer.
 */
public class HotReloadClassFileTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,               // "/" separated internal form
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer)
            throws IllegalClassFormatException {

        // Skip JDK / agent classes to avoid corrupting the runtime.
        if (className.startsWith("java/") ||
            className.startsWith("javax/") ||
            className.startsWith("jdk/") ||
            className.startsWith("com/example/hotreload")) {
            return null;
        }

        // No load-time transformation. Bytecode changes are applied via
        // redefineClasses when a .class file changes on disk.
        return null;
    }
}
