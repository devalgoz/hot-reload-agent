package com.example.hotreload.gradle.plugin;

import com.example.hotreload.build.SourceCompileWatcher;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Gradle plugin that integrates the Hot-Reload Agent into a Java project.
 *
 * <p>Apply the plugin:</p>
 * <pre>
 *   plugins {
 *       id 'com.example.hotreload' version '1.0.0-SNAPSHOT'
 *   }
 * </pre>
 *
 * <p>Tasks provided:</p>
 * <ul>
 *   <li>{@code hotReloadWatch} — watches {@code src/main/java} and auto-compiles
 *       changes into {@code build/classes/java/main}.</li>
 *   <li>{@code hotReloadAttach} — attaches the agent to a running JVM by PID.
 *       Configure via {@code -PhotReload.pid=... -PhotReload.agentJar=...}.</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 *   # Start the watcher
 *   ./gradlew hotReloadWatch
 *
 *   # Attach to a running JVM (in another terminal)
 *   ./gradlew hotReloadAttach \
 *       -PhotReload.pid=12345 \
 *       -PhotReload.agentJar=../agent/target/agent-1.0.0-SNAPSHOT.jar \
 *       -PhotReload.watchDir=build/classes/java/main
 * </pre>
 */
public class HotReloadPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        ProjectLayout layout = project.getLayout();

        // --- hotReloadWatch task ---
        TaskProvider<Task> watchTask = project.getTasks().register("hotReloadWatch", task -> {
            task.setGroup("hot-reload");
            task.setDescription("Watches Java sources and auto-compiles on change for hot-reloading");

            File sourceDir = project.file("src/main/java");
            Directory outputDir = layout.getBuildDirectory().dir("classes/java/main").get();

            task.doLast(ignored -> {
                project.getLogger().info("Starting source compile watcher...");
                project.getLogger().info("  Source dir: " + sourceDir.getAbsolutePath());
                project.getLogger().info("  Output dir: " + outputDir.getAsFile().getAbsolutePath());

                outputDir.getAsFile().mkdirs();

                try {
                    SourceCompileWatcher watcher = new SourceCompileWatcher(
                            sourceDir.toPath(), outputDir.getAsFile().toPath(), new ArrayList<>());

                    Runtime.getRuntime().addShutdownHook(
                            new Thread(watcher::stop, "hot-reload-watcher-shutdown"));
                    watcher.start();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to start source compile watcher", e);
                }
            });
        });

        // --- hotReloadAttach task ---
        Directory defaultOutputDir = layout.getBuildDirectory().dir("classes/java/main").get();
        TaskProvider<JavaExec> attachTask = project.getTasks().register("hotReloadAttach", JavaExec.class, task -> {
            task.setGroup("hot-reload");
            task.setDescription("Attaches the Hot-Reload Agent to a running JVM by PID");
            task.getMainClass().set("com.example.hotreload.remote.RemoteAttachClient");

            String pid = project.findProperty("hotReload.pid") != null
                    ? project.findProperty("hotReload.pid").toString()
                    : "";
            String agentJar = project.findProperty("hotReload.agentJar") != null
                    ? project.findProperty("hotReload.agentJar").toString()
                    : "";
            String watchDir = project.findProperty("hotReload.watchDir") != null
                    ? project.findProperty("hotReload.watchDir").toString()
                    : defaultOutputDir.getAsFile().getAbsolutePath();

            List<String> args = new ArrayList<>();
            if (!pid.isBlank()) args.add(pid);
            if (!agentJar.isBlank()) args.add(agentJar);
            if (!watchDir.isBlank()) args.add(watchDir);

            for (String arg : args) {
                task.getArgs().add(arg);
            }

            task.onlyIf(t -> !pid.isBlank() && !agentJar.isBlank());
        });
    }
}
