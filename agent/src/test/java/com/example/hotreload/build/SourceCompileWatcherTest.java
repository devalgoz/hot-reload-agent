package com.example.hotreload.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SourceCompileWatcher} — auto-compilation of {@code .java}
 * sources on change.
 */
class SourceCompileWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void compilesSimpleJavaFile() throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("classes");
        Files.createDirectories(sourceDir.resolve("com").resolve("example"));

        Path javaFile = sourceDir.resolve("com").resolve("example").resolve("Greeter.java");
        Files.writeString(javaFile,
                "package com.example;\n"
                + "public class Greeter {\n"
                + "  public String greet() { return \"hi\"; }\n"
                + "}\n");

        SourceCompileWatcher watcher = new SourceCompileWatcher(sourceDir, outputDir,
                java.util.List.of());

        int compiled = watcher.compileAll();
        assertEquals(1, compiled);

        Path classFile = outputDir.resolve("com").resolve("example").resolve("Greeter.class");
        assertTrue(Files.exists(classFile), "Compiled .class file should exist in output dir");

        watcher.stop();
    }

    @Test
    void requiresJdkNotJustJre() {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        // In a JDK environment this is non-null; the watcher fails fast otherwise.
        if (compiler == null) {
            assertThrows(IllegalStateException.class, () ->
                    new SourceCompileWatcher(tempDir, tempDir, java.util.List.of()));
        } else {
            assertNotNull(compiler);
        }
    }
}
