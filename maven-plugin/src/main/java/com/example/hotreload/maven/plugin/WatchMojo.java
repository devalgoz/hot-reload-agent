package com.example.hotreload.maven.plugin;

import com.example.hotreload.build.SourceCompileWatcher;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts a file watcher that auto-compiles {@code .java} sources on change and
 * deposits {@code .class} files into {@code target/classes}, where the
 * Hot-Reload Agent's {@link com.example.hotreload.ReloadService} is already
 * watching.
 *
 * <p>This goal blocks while running (it starts a daemon watcher thread and
 * keeps the Maven session alive). Use it in a separate Maven invocation or
 * bind it to a profile that you run manually.</p>
 *
 * <pre>
 *   mvn com.example.hotreload:hot-reload-maven-plugin:1.0.0-SNAPSHOT:watch \
 *     -DhotReload.sourceDir=src/main/java \
 *     -DhotReload.outputDir=target/classes
 * </pre>
 */
@Mojo(name = "watch", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public class WatchMojo extends AbstractMojo {

    /**
     * Directory containing {@code .java} source files to watch.
     */
    @Parameter(property = "hotReload.sourceDir", defaultValue = "src/main/java")
    private File sourceDir;

    /**
     * Directory to write compiled {@code .class} files (watched by the agent).
     */
    @Parameter(property = "hotReload.outputDir",
            defaultValue = "${project.build.outputDirectory}")
    private File outputDir;

    /**
     * Additional classpath entries (JARs or directories).
     */
    @Parameter(property = "hotReload.classpath")
    private String classpath;

    @Override
    public void execute() throws MojoExecutionException {
        if (!sourceDir.exists()) {
            throw new MojoExecutionException("Source directory not found: " + sourceDir.getAbsolutePath());
        }

        outputDir.mkdirs();

        List<String> cp = new ArrayList<>();
        cp.add(outputDir.getAbsolutePath());
        if (classpath != null && !classpath.isBlank()) {
            for (String entry : classpath.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    cp.add(entry);
                }
            }
        }

        getLog().info("Starting source compile watcher...");
        getLog().info("  Source dir: " + sourceDir.getAbsolutePath());
        getLog().info("  Output dir: " + outputDir.getAbsolutePath());
        getLog().info("  Classpath: " + String.join(File.pathSeparator, cp));

        try {
            SourceCompileWatcher watcher = new SourceCompileWatcher(
                    sourceDir.toPath(), outputDir.toPath(), cp);

            Runtime.getRuntime().addShutdownHook(new Thread(watcher::stop, "hot-reload-watcher-shutdown"));
            watcher.start();
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to start source compile watcher", e);
        }
    }
}
