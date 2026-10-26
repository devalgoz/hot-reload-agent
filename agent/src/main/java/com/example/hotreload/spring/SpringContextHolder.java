package com.example.hotreload.spring;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * Holds a static reference to the running Spring {@link ConfigurableApplicationContext}.
 *
 * <p>This enables auto-discovery of the Spring context without requiring
 * explicit {@link SpringBeanReloader#setContext(ConfigurableApplicationContext)}
 * calls. {@link SpringAgentIntegration} registers itself here on startup, and
 * any component can call {@link #getContext()} to retrieve it.</p>
 *
 * <p>Usage in a Spring Boot application:</p>
 * <pre>
 *   @Component
 *   public class MyContextHolder implements ApplicationContextAware {
 *       public void setApplicationContext(ApplicationContext context) {
 *           SpringContextHolder.register((ConfigurableApplicationContext) context);
 *       }
 *   }
 * </pre>
 */
public final class SpringContextHolder {

    private static volatile ConfigurableApplicationContext context;

    private SpringContextHolder() {
    }

    public static void register(ConfigurableApplicationContext ctx) {
        context = ctx;
        System.out.println("[SpringContextHolder] Spring context registered: "
                + ctx.getApplicationName());
    }

    public static void clear() {
        context = null;
    }

    public static ConfigurableApplicationContext getContext() {
        return context;
    }

    public static boolean isRegistered() {
        return context != null && context.isRunning();
    }
}
