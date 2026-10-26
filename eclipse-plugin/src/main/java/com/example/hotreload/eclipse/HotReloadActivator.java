package com.example.hotreload.eclipse;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.BundleContext;

/**
 * Eclipse plugin activator for the Hot-Reload Agent integration.
 *
 * <p>This is a skeleton activator. To make it fully functional, wire it to
 * compile on save and push class bytes to a running Hot-Reload Agent.</p>
 */
public class HotReloadActivator extends Plugin {

    private static HotReloadActivator instance;

    public HotReloadActivator() {
        instance = this;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        getLog().info("Hot-Reload Agent Eclipse plugin started");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
        getLog().info("Hot-Reload Agent Eclipse plugin stopped");
    }

    public static HotReloadActivator getDefault() {
        return instance;
    }
}
