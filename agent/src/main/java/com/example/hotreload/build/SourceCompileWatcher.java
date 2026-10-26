package com.example.hotreload.build;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Watches Java source files for changes, compiles them on-the-fly, and
 * deposits the resulting {@code .class} files into the output directory where
 * the Hot-Reload Agent's {@link com.example.hotreload.ReloadService} is
 * already watching.
 *
 * This closes the build/watch gap: edit a {@code .java} file, it compiles
 * automatically, the new {@code .class} appears on disk, and the agent
 * hot-reloads the changed class — no manual {@code mvn compile} or IDE build
 * required.
 *
 * Usage:
 *   java -cp hot-reload-agent.jar:target/classes:libs/* \
 *       com.example.hotreload.build.SourceCompileWatcher <source-dir> <output-dir> [classpath-jars...]
 *
 * Example (run alongside a hot-reloaded application):
 *   java -cp target/hot-reload-agent-1.0.0-SNAPSHOT.jar:target/classes \
 *       com.example.hotreload.build.SourceCompileWatcher \
 *       src/main/java target/classes
 *
 * How it works:
 *   1. Performs an initial compile of all {@code .java} files in source-dir.
 *   2. Registers a WatchService on source-dir (recursively) for
 *      ENTRY_CREATE / ENTRY_MODIFY on {@code .java} files.
 *   3. On change, recompiles the affected file(s) into output-dir.
 *   4. The agent's ReloadService detects the new {@code .class} and triggers
 *      the hot-reload pipeline (redefineClasses or structural classloader).
 *
 * Limitations:
 *   - Compiles only the changed file; cross-file dependency tracking is
 *     limited to the compiler's sourcepath resolution.
 *   - Does not handle deleted source files (no corresponding .class removal).
 *   - Requires a JDK (not just a JRE) because it uses javax.tools.JavaCompiler.
 *   - Java 17+ target (matches the agent's compiler source/target level).
 */
public class SourceCompileWatcher {

    private final Path sourceDir;
    private final Path outputDir;
    private final List<String> classpath;
    private final WatchService watchService;
    private final JavaCompiler compiler;
    private final StandardJavaFileManager fileManager;
    private final Set<Path> watchedDirs = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    public SourceCompileWatcher(Path sourceDir, Path outputDir, List<String> classpath) throws IOException {
        this.sourceDir = sourceDir.toAbsolutePath().normalize();
        this.outputDir = outputDir.toAbsolutePath().normalize();
        this.classpath = classpath != null ? classpath : new ArrayList<>();

        // Ensure output directory exists.
        Files.createDirectories(this.outputDir);

        this.watchService = FileSystems.getDefault().newWatchService();
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (this.compiler == null) {
            throw new IllegalStateException("No system JavaCompiler available — a JDK is required, not just a JRE");
        }
        this.fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
    }

    /** Start watching and compiling. Blocks until interrupted. */
    public void start() throws Exception {
        // Initial full compile.
        System.out.println("[SourceCompileWatcher] Initial compile of " + sourceDir);
        int initial = compileAll();
        System.out.println("[SourceCompileWatcher] Initial compile complete: " + initial + " source file(s) compiled");

        // Register the root directory and all existing subdirectories.
        registerDirectory(sourceDir);

        System.out.println("[SourceCompileWatcher] Watching " + sourceDir + " for source changes...");

        Thread watchThread = new Thread(this::watchLoop, "source-compile-watcher");
        watchThread.setDaemon(true);
        watchThread.start();

        // Keep the main thread alive while running.
        while (running) {
            Thread.sleep(1000);
        }
    }

    public void stop() {
        running = false;
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
        try {
            fileManager.close();
        } catch (IOException ignored) {
        }
    }

    /** Compile every .java file under sourceDir into outputDir. */
    int compileAll() throws IOException {
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            sources = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        if (sources.isEmpty()) {
            return 0;
        }
        return compile(sources);
    }

    /** Compile a specific list of source files. */
    private int compile(List<Path> sources) throws IOException {
        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(outputDir.toString());
        options.add("-sourcepath");
        options.add(sourceDir.toString());
        options.add("-cp");
        options.add(String.join(File.pathSeparator, classpath));
        options.add("-source");
        options.add("17");
        options.add("-target");
        options.add("17");

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, null, options, null,
                fileManager.getJavaFileObjectsFromPaths(sources)
        );

        Boolean success = task.call();
        return success != null && success ? sources.size() : 0;
    }

    /** Register a directory with the WatchService, and recurse into subdirectories. */
    private void registerDirectory(Path dir) throws IOException {
        if (watchedDirs.contains(dir)) {
            return;
        }
        dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        watchedDirs.add(dir);

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                  .forEach(sub -> {
                      try {
                          registerDirectory(sub);
                      } catch (IOException e) {
                          System.err.println("[SourceCompileWatcher] Could not watch " + sub + ": " + e.getMessage());
                      }
                  });
        }
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            Path dir = (Path) key.watchable();
            List<Path> changedJava = new ArrayList<>();
            boolean dirCreated = false;

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                Path child = dir.resolve(filename);

                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                    // New subdirectory appeared — start watching it too.
                    try {
                        registerDirectory(child);
                        dirCreated = true;
                    } catch (IOException e) {
                        System.err.println("[SourceCompileWatcher] Could not register new directory " + child
                                + ": " + e.getMessage());
                    }
                    continue;
                }

                if (filename.toString().endsWith(".java")) {
                    changedJava.add(child);
                    System.out.println("[SourceCompileWatcher] Source change detected: " + child
                            + " (" + kind.name() + ")");
                }
            }

            if (!changedJava.isEmpty()) {
                try {
                    // Filter out deleted files — we can only compile existing files.
                    List<Path> compilable = changedJava.stream()
                            .filter(Files::exists)
                            .collect(Collectors.toList());
                    if (!compilable.isEmpty()) {
                        int compiled = compile(compilable);
                        System.out.println("[SourceCompileWatcher] Recompiled " + compiled
                                + " source file(s); output to " + outputDir);
                    }
                } catch (Exception e) {
                    System.err.println("[SourceCompileWatcher] Compilation failed: " + e.getMessage());
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                watchedDirs.remove(dir);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SourceCompileWatcher <source-dir> <output-dir> [classpath-jars...]");
            System.err.println("  source-dir    - Directory containing .java source files to watch");
            System.err.println("  output-dir    - Directory to write compiled .class files (watched by the agent)");
            System.err.println("  classpath-jars - Optional additional classpath entries (jars or dirs)");
            System.exit(1);
        }

        Path sourceDir = Paths.get(args[0]).toAbsolutePath().normalize();
        Path outputDir = Paths.get(args[1]).toAbsolutePath().normalize();

        List<String> classpath = new ArrayList<>();
        // Always include the output dir so the compiler can find previously compiled classes.
        classpath.add(outputDir.toString());
        // Add the source dir itself as sourcepath is set separately.
        for (int i = 2; i < args.length; i++) {
            classpath.add(args[i]);
        }

        SourceCompileWatcher watcher = new SourceCompileWatcher(sourceDir, outputDir, classpath);

        Runtime.getRuntime().addShutdownHook(new Thread(watcher::stop, "source-compile-watcher-shutdown"));
        watcher.start();
    }
}
