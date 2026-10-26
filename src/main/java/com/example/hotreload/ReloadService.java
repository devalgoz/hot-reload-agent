package com.example.hotreload;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Watches a directory for compiled {@code .class} changes and dispatches
 * reloads to registered {@link HotReloadAgent.ReloadListener}s.
 *
 * The directory to watch is taken from the agent argument (e.g.
 * {@code -javaagent:...jar=target/classes}) or the {@code hot.reload.dir}
 * system property, defaulting to {@code target/classes}.
 */
public class ReloadService {

    private final WatchService watchService;
    private final List<HotReloadAgent.ReloadListener> listeners = new ArrayList<>();
    private volatile boolean running = true;
    private Path watchDir;

    public ReloadService() throws IOException {
        this.watchService = FileSystems.getDefault().newWatchService();
    }

    public void start(String agentArgs) {
        Path dir = resolveWatchDir(agentArgs);
        this.watchDir = dir.toAbsolutePath().normalize();

        try {
            watchDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            System.err.println("[HotReloadAgent] Could not watch " + watchDir
                    + ": " + e.getMessage());
            return;
        }

        System.out.println("[HotReloadAgent] Watching " + watchDir
                + " for class changes...");

        Thread watchThread = new Thread(this::watchLoop, "hot-reload-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    public void addListener(HotReloadAgent.ReloadListener listener) {
        listeners.add(listener);
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

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                Path child = watchDir.resolve(filename);

                if (filename.toString().endsWith(".class")) {
                    System.out.println("[HotReloadAgent] Detected change: " + child);
                    try {
                        dispatchReload(child);
                    } catch (Exception e) {
                        System.err.println("[HotReloadAgent] Reload failed for "
                                + child + ": " + e.getMessage());
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                break;
            }
        }
    }

    private void dispatchReload(Path classFile) throws Exception {
        String relativePath = watchDir.relativize(classFile).toString();
        String className = relativePath
                .replace(FileSystems.getDefault().getSeparator(), "/")
                .replace(".class", "")
                .replace("/", ".");

        // Never reload JDK / agent classes.
        if (className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("jdk.") ||
            className.startsWith("com.example.hotreload")) {
            return;
        }

        byte[] bytes = java.nio.file.Files.readAllBytes(classFile);

        // 1. Try redefineClass (method-body changes). If structural, fall back
        //    to StructuralClassLoader + ByteBuddy engine.
        HotReloadAgent.reloadClass(className, watchDir, classFile, bytes);

        // 2. Notify all listeners (Spring, Hibernate, custom).
        for (HotReloadAgent.ReloadListener listener : listeners) {
            try {
                listener.onClassReloaded(className, bytes);
            } catch (Exception e) {
                System.err.println("[HotReloadAgent] Listener error for "
                        + className + ": " + e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
    }

    private static Path resolveWatchDir(String agentArgs) {
        // Agent argument takes precedence, e.g. -javaagent:...jar=target/classes
        String dir = (agentArgs != null && !agentArgs.isBlank()) ? agentArgs.trim()
                : System.getProperty("hot.reload.dir");
        if (dir == null || dir.isBlank()) {
            dir = "target/classes";
        }
        return Paths.get(dir);
    }
}
