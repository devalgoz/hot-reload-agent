package com.example.hotreload.spring;

import com.example.hotreload.HotReloadAgent;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Watches Spring configuration files (application.properties / application.yml)
 * and hot-reloads them into the running {@link ConfigurableEnvironment}.
 *
 * What this does:
 *   - Detects changes to application.properties / application.yml on disk.
 *   - Reloads the properties into a {@link PropertiesPropertySource} placed at
 *     the front of the environment's property sources so new values win.
 *
 * Limitations (be honest):
 *   - @Value and @ConfigurationProperties beans are populated at context
 *     startup. Changing the property source alone does NOT re-inject existing
 *     beans. You need Spring Cloud @RefreshScope (or a targeted bean evict like
 *     SpringBeanReloader) to push new values into live beans.
 *   - YAML support here is minimal (properties only). Add SnakeYAML for full YAML.
 */
public class SpringConfigReloader implements HotReloadAgent.ReloadListener {

    private volatile ConfigurableEnvironment environment;

    public void setEnvironment(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public void onClassReloaded(String className, byte[] newBytes) {
        // This listener is wired to fire only for config files, not .class files.
        // See ReloadConfigWatcher in the target application for how it's invoked.
    }

    /**
     * Called directly by the file watcher when a config file changes.
     */
    public void onConfigChanged(String filePath) {
        if (environment == null) {
            return;
        }

        System.out.println("[SpringConfigReloader] Config changed: " + filePath);

        try {
            Properties props = loadProperties(filePath);
            String sourceName = "hot-reloaded-" + filePath.replaceAll("[^a-zA-Z0-9]", "_");

            MutablePropertySources sources = environment.getPropertySources();

            // Remove previous hot-reloaded source for the same file, then add fresh.
            if (sources.contains(sourceName)) {
                sources.remove(sourceName);
            }
            sources.addFirst(new PropertiesPropertySource(sourceName, props));

            System.out.println("[SpringConfigReloader] Property source refreshed: " + sourceName);

        } catch (IOException e) {
            System.err.println("[SpringConfigReloader] Failed to reload config "
                    + filePath + ": " + e.getMessage());
        }
    }

    private Properties loadProperties(String filePath) throws IOException {
        Properties props = new Properties();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        // Try classpath first, then file system.
        org.springframework.core.io.Resource[] resources = resolver.getResources(
                "classpath*:" + filePath);
        if (resources.length == 0) {
            resources = resolver.getResources("file:" + filePath);
        }

        for (org.springframework.core.io.Resource res : resources) {
            try (InputStream is = res.getInputStream()) {
                props.load(is);
            }
        }
        return props;
    }
}
