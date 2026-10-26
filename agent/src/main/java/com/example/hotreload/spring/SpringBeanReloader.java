package com.example.hotreload.spring;

import com.example.hotreload.HotReloadAgent;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Hot-reloads Spring beans when their compiled class changes on disk.
 *
 * Strategy — TARGETED (no full context restart):
 *   1. Detect the changed class name.
 *   2. Look up ALL beans of that type in the ConfigurableApplicationContext
 *      (not just stereotype-annotated ones — any Spring-managed bean).
 *   3. Destroy the stale singleton instance.
 *   4. Re-acquire the bean via getBean() so Spring re-instantiates it from
 *      the existing bean definition using the redefined class.
 *   5. For @RefreshScope beans, also re-inject @Value fields from the
 *      refreshed environment.
 *
 * This avoids {@link ConfigurableApplicationContext#refresh()}, which would
 * restart the whole application. Only the changed bean (and beans that look it
 * up afresh) are affected.
 *
 * The ConfigurableApplicationContext is injected by the target application
 * (e.g. via SpringAgentIntegration or a small @Configuration bean that calls
 * setContext()).
 *
 * Limitations:
 *   - Beans obtained via ObjectProvider / ObjectFactory (lazy proxies) may
 *     still hold stale references until next lookup.
 *   - CGLIB-proxied concrete classes (classes without interfaces) are
 *     re-instantiated but existing direct references remain stale.
 *   - @Value re-injection requires Spring Cloud @RefreshScope or manual
 *     invocation of refreshValue().
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

        if (!isSpringManagedBean(clazz)) {
            return;
        }

        System.out.println("[SpringBeanReloader] Reloading Spring bean: " + className);

        try {
            ConfigurableListableBeanFactory bf = context.getBeanFactory();

            // Find ALL bean names whose type matches the changed class.
            // Include non-singletons and beans matched via inheritance.
            List<String> beanNames = new ArrayList<>();
            for (String name : bf.getBeanDefinitionNames()) {
                try {
                    Class<?> beanType = bf.getType(name);
                    if (beanType != null && clazz.isAssignableFrom(beanType)) {
                        beanNames.add(name);
                    }
                } catch (BeansException e) {
                    // Bean type unresolvable — skip.
                }
            }

            // Also check beans that implement/extend the changed class via getBeanNamesForType.
            for (String name : bf.getBeanNamesForType(clazz, false, false)) {
                if (!beanNames.contains(name)) {
                    beanNames.add(name);
                }
            }

            // destroySingleton lives on DefaultListableBeanFactory.
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

                // Re-inject @Value fields for @RefreshScope beans.
                injectValueFields(fresh, context.getEnvironment());
            }

        } catch (BeansException e) {
            System.err.println("[SpringBeanReloader] Failed to reload "
                    + className + ": " + e.getMessage());
        }
    }

    /**
     * Determine if a class is a Spring-managed bean. Previously this only
     * checked for @Service/@Controller/@Repository/@Component. Now we also
     * accept any class that is registered as a bean definition in the
     * context, plus common Spring stereotype annotations.
     */
    private boolean isSpringManagedBean(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }

        // Stereotype annotations.
        if (AnnotatedElementUtils.hasAnnotation(clazz, org.springframework.stereotype.Service.class)
            || AnnotatedElementUtils.hasAnnotation(clazz, org.springframework.stereotype.Controller.class)
            || AnnotatedElementUtils.hasAnnotation(clazz, org.springframework.stereotype.Repository.class)
            || AnnotatedElementUtils.hasAnnotation(clazz, org.springframework.stereotype.Component.class)) {
            return true;
        }

        // @Scope annotation (prototype, singleton, request, session) often
        // marks a Spring-managed bean.
        if (AnnotatedElementUtils.hasAnnotation(clazz, Scope.class)) {
            return true;
        }

        // If the context is available, check whether a bean definition exists
        // for this class.
        if (context != null) {
            try {
                ConfigurableListableBeanFactory bf = context.getBeanFactory();
                for (String name : bf.getBeanDefinitionNames()) {
                    Class<?> beanType = bf.getType(name);
                    if (beanType != null && beanType.equals(clazz)) {
                        return true;
                    }
                }
                // Also check pre-registered singletons (e.g. registerSingleton).
                for (String name : bf.getSingletonNames()) {
                    Object singleton = bf.getSingleton(name);
                    if (singleton != null && singleton.getClass().equals(clazz)) {
                        return true;
                    }
                }
            } catch (BeansException e) {
                // Context not fully initialized — fall back to annotation check.
            }
        }

        return false;
    }

    /**
     * Re-inject {@code @Value} fields on a bean using the current Environment.
     * This is primarily useful for beans annotated with Spring Cloud's
     * {@code @RefreshScope}. Call this after a context refresh or property
     * source reload.
     */
    private void injectValueFields(Object bean, org.springframework.core.env.Environment env) {
        if (bean == null || env == null) {
            return;
        }

        Class<?> clazz = bean.getClass();
        int injected = 0;

        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(Value.class)) {
                    field.setAccessible(true);
                    try {
                        Value annotation = field.getAnnotation(Value.class);
                        String expression = annotation.value();
                        // Resolve ${...} placeholders against the environment.
                        String resolved = env.resolveRequiredPlaceholders(expression);
                        // Convert to field type.
                        Object value = convertValue(resolved, field.getType());
                        field.set(bean, value);
                        injected++;
                        System.out.println("[SpringBeanReloader] Re-injected @Value field: "
                                + field.getName() + " = " + resolved);
                    } catch (Exception e) {
                        System.err.println("[SpringBeanReloader] Failed to inject @Value "
                                + field.getName() + ": " + e.getMessage());
                    }
                }
            }
        }

        if (injected > 0) {
            System.out.println("[SpringBeanReloader] Re-injected " + injected
                    + " @Value field(s) on " + clazz.getName());
        }
    }

    @SuppressWarnings("unchecked")
    private Object convertValue(String value, Class<?> type) {
        if (type == String.class) {
            return value;
        }
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(value);
        }
        if (type == long.class || type == Long.class) {
            return Long.parseLong(value);
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(value);
        }
        if (type == double.class || type == Double.class) {
            return Double.parseDouble(value);
        }
        if (type == float.class || type == Float.class) {
            return Float.parseFloat(value);
        }
        return value;
    }
}
