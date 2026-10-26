package com.example.hotreload.maven.plugin;

import com.example.hotreload.remote.RemoteAttachClient;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Attaches the Hot-Reload Agent to a running JVM by process ID.
 *
 * <p>Usage:</p>
 * <pre>
 *   mvn com.example.hotreload:hot-reload-maven-plugin:1.0.0-SNAPSHOT:attach \
 *     -DhotReload.pid=12345 \
 *     -DhotReload.agentJar=target/hot-reload-agent-1.0.0-SNAPSHOT.jar \
 *     -DhotReload.watchDir=target/classes
 * </pre>
 */
@Mojo(name = "attach", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public class AttachMojo extends AbstractMojo {

    /**
     * Target JVM process ID.
     */
    @Parameter(property = "hotReload.pid", required = true)
    private String pid;

    /**
     * Path to the agent JAR file.
     */
    @Parameter(property = "hotReload.agentJar",
            defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}.jar")
    private File agentJar;

    /**
     * Directory the agent watches for {@code .class} changes.
     */
    @Parameter(property = "hotReload.watchDir", defaultValue = "target/classes")
    private String watchDir;

    @Override
    public void execute() throws MojoExecutionException {
        if (!agentJar.exists()) {
            throw new MojoExecutionException("Agent JAR not found: " + agentJar.getAbsolutePath());
        }

        getLog().info("Attaching Hot-Reload Agent to JVM " + pid + "...");
        getLog().info("  Agent JAR: " + agentJar.getAbsolutePath());
        getLog().info("  Watch dir: " + watchDir);

        try {
            String[] args = {pid, agentJar.getAbsolutePath(), watchDir};
            RemoteAttachClient.main(args);
            getLog().info("Agent attached successfully to JVM " + pid);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to attach agent to JVM " + pid, e);
        }
    }
}
