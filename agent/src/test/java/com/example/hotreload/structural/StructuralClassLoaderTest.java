package com.example.hotreload.structural;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StructuralClassLoader} — the isolated classloader used to
 * load structurally-modified versions of classes from disk.
 */
class StructuralClassLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsClassFromCompiledClassFile() throws Exception {
        // Compile a simple test class into the temp dir.
        Path javaFile = tempDir.resolve("com").resolve("example").resolve("demo");
        Files.createDirectories(javaFile);
        Path source = javaFile.resolve("TestGreeter.java");
        Files.writeString(source,
                "package com.example.demo;\n"
                + "public class TestGreeter {\n"
                + "  public String greet() { return \"hi\"; }\n"
                + "}\n");

        // Use the system compiler to produce a .class file.
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK is required to compile the test fixture");

        int result = compiler.run(null, null, null,
                "-d", tempDir.toString(),
                source.toString());
        assertEquals(0, result, "Test fixture compilation should succeed");

        Path classFile = tempDir.resolve("com").resolve("example").resolve("demo")
                .resolve("TestGreeter.class");
        assertTrue(Files.exists(classFile), "Compiled .class file should exist");

        // Load it via StructuralClassLoader (factory method; ctor is private).
        StructuralClassLoader scl = StructuralClassLoader.nextVersion(tempDir,
                StructuralClassLoaderTest.class.getClassLoader());
        Class<?> clazz = scl.loadClassFromDir("com.example.demo.TestGreeter");
        assertNotNull(clazz);
        assertEquals("com.example.demo.TestGreeter", clazz.getName());

        Object instance = clazz.getDeclaredConstructor().newInstance();
        Object resultObj = clazz.getMethod("greet").invoke(instance);
        assertEquals("hi", resultObj);
    }

    @Test
    void versionIncrementsPerDirectory() {
        Path dirA = tempDir.resolve("a");
        Path dirB = tempDir.resolve("b");
        try {
            Files.createDirectories(dirA);
            Files.createDirectories(dirB);
        } catch (java.io.IOException e) {
            fail("Failed to create temp dirs", e);
        }

        StructuralClassLoader v1 = StructuralClassLoader.nextVersion(dirA,
                StructuralClassLoaderTest.class.getClassLoader());
        StructuralClassLoader v2 = StructuralClassLoader.nextVersion(dirA,
                StructuralClassLoaderTest.class.getClassLoader());
        StructuralClassLoader vB = StructuralClassLoader.nextVersion(dirB,
                StructuralClassLoaderTest.class.getClassLoader());

        assertEquals(1, v1.getVersion());
        assertEquals(2, v2.getVersion());
        // Different directory gets its own counter starting at 1.
        assertEquals(1, vB.getVersion());
    }
}
