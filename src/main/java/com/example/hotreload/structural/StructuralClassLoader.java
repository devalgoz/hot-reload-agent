package com.example.hotreload.structural;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ClassLoader} that loads structurally-modified class versions from
 * {@code .class} files on disk.
 *
 * Each structural change creates a new child {@code StructuralClassLoader},
 * so class versions are isolated:
 *   - v1 of {@code com.example.demo.Greeter} is loaded by the app classloader
 *   - v2 of {@code com.example.demo.Greeter} is loaded by a new child loader
 *
 * This is how JRebel sidesteps the JVM Instrumentation limit: you cannot
 * redefine a loaded class to add/remove members, but you *can* load a new
 * version of the same class name in a different {@link ClassLoader}.
 */
public class StructuralClassLoader extends URLClassLoader {

    /** Tracks the next version number per watched class directory. */
    private static final ConcurrentMap<Path, AtomicInteger> versionCounter = new ConcurrentHashMap<>();

    private final Path classDir;
    private final int version;

    private StructuralClassLoader(Path classDir, ClassLoader parent, int version) {
        super(new URL[0], parent);
        this.classDir = classDir;
        this.version = version;
    }

    /**
     * Create a new classloader for the next version of classes in {@code classDir}.
     */
    public static StructuralClassLoader nextVersion(Path classDir, ClassLoader parent) {
        int v = versionCounter.computeIfAbsent(classDir, k -> new AtomicInteger(0)).incrementAndGet();
        return new StructuralClassLoader(classDir, parent, v);
    }

    public int getVersion() {
        return version;
    }

    /**
     * Define a class from its raw .class file bytes.
     */
    public Class<?> defineClassFromFile(String className, Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        return defineClass(className, bytes, 0, bytes.length);
    }

    /**
     * Load a class by name from the watched directory, defining it if not
     * already loaded by this classloader.
     */
    public Class<?> loadClassFromDir(String className) throws ClassNotFoundException, IOException {
        // Check if already loaded by this classloader.
        Class<?> already = findLoadedClass(className);
        if (already != null) {
            return already;
        }

        Path classFile = classDir.resolve(className.replace('.', '/') + ".class");
        if (!Files.exists(classFile)) {
            throw new ClassNotFoundException(className + " not found in " + classDir);
        }
        return defineClassFromFile(className, classFile);
    }
}
