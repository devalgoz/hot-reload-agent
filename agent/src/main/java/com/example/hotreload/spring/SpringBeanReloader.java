package com.example.hotreload.spring;

import com.example.hotreload.HotReloadAgent;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Hot-reloads Spring beans (@Service, @Controller, @Repository, @Component)
 * when their compiled class changes on disk.
 *
 * Strategy — TARGETED (no full context restart):
 *   1. Detect the changed class name.
 *   2. Look up beans of that type in the ConfigurableApplicationContext.
 *   3. Destroy the stale singleton instance.
 *   4. Re-acquire the bean via getBean() so Spring re-instantiates it from the
 *      existing bean definition using the redefined class.
 *
 * This avoids {@link ConfigurableApplicationContext#refresh()}, which would
 * restart the whole application. Only the changed bean (and beans that look it
 * up afresh) are affected.
 *
 * The ConfigurableApplicationContext is injected by the target application
 * (e.g. via a small @Configuration bean that calls setContext()).
 */
public class SpringBeanReloader implements HotReloadAgent.ReloadListener {

    private volatile ConfigurableApplicationContext context;

    public void setContext(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void onClassReloaded(String className, byte[] newBytes) {
        if (context == null || !context.isRunning()) {
            return;
        }

        Class<?> clazz;
        try {
            clazz = Class.forName(className, false,
                    Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            return; // class not yet loadable
        }

        if (!isSpringBean(clazz)) {
            return;
        }

        System.out.println("[SpringBeanReloader] Reloading Spring bean: " + className);

        try {
            ConfigurableListableBeanFactory bf = context.getBeanFactory();

            // Find bean names whose type matches the changed class.
            String[] beanNames = bf.getBeanNamesForType(clazz, false, false);

            // destroySingleton lives on DefaultListableBeanFactory (the runtime
            // impl returned by getBeanFactory()), not on the ConfigurableListableBeanFactory
            // interface itself.
            org.springframework.beans.factory.support.DefaultListableBeanFactory dlbf =
                    (org.springframework.beans.factory.support.DefaultListableBeanFactory) bf;

            for (String beanName : beanNames) {
                if (dlbf.containsSingleton(beanName)) {
                    // Destroy the stale instance.
                    dlbf.destroySingleton(beanName);
                    System.out.println("[SpringBeanReloader] Destroyed stale bean: " + beanName);
                }

                // Force re-instantiation from the bean definition.
                // Spring will create a new instance using the redefined class.
                Object fresh = bf.getBean(beanName);
                System.out.println("[SpringBeanReloader] Recreated bean: " + beanName
                        + " -> " + fresh.getClass().getName());
            }

        } catch (BeansException e) {
            System.err.println("[SpringBeanReloader] Failed to reload "
                    + className + ": " + e.getMessage());
        }
    }

    private boolean isSpringBean(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return clazz.isAnnotationPresent(org.springframework.stereotype.Service.class)
            || clazz.isAnnotationPresent(org.springframework.stereotype.Controller.class)
            || clazz.isAnnotationPresent(org.springframework.stereotype.Repository.class)
            || clazz.isAnnotationPresent(org.springframework.stereotype.Component.class);
    }
}
