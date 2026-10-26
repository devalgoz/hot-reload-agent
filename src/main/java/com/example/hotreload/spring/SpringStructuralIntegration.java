package com.example.hotreload.spring;

import com.example.hotreload.HotReloadAgent;
import com.example.hotreload.structural.HotSwapProxy;
import com.example.hotreload.structural.StructuralHotSwapManager;
import com.example.hotreload.structural.StructuralHotSwapManager.StructuralSwapListener;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Spring integration for transparent structural hot-swapping.
 *
 * Implements {@link BeanPostProcessor} so that after Spring creates any bean,
 * we wrap it in a {@link HotSwapProxy} (if it has interfaces). The proxy is
 * registered with {@link StructuralHotSwapManager} and returned to Spring
 * instead of the raw bean. All injection points receive the proxy.
 *
 * On structural swap, {@link StructuralHotSwapManager} updates the proxy's
 * current instance. All existing proxy holders transparently see the new
 * version — achieving indirection-based transparency without JVMTI.
 *
 * Limitations:
 *   - Only interface-based beans can be proxied (JDK dynamic proxy).
 *   - Classes without interfaces cannot be proxied this way (would need
 *     CGLIB/ByteBuddy subclass proxy).
 *   - Direct field access on the target object bypasses the proxy.
 *   - Identity checks (==) compare the proxy, not the underlying instance.
 *   - True heap-level transparency requires native JVMTI (JRebel's approach).
 */
@Component
public class SpringStructuralIntegration implements ApplicationContextAware, BeanPostProcessor, StructuralHotSwapManager.StructuralSwapListener {

    private ConfigurableListableBeanFactory beanFactory;
    private Path classDir;

    /** Tracks beanName -> interfaces for proxy creation. */
    private final Map<String, Class<?>[]> beanInterfaces = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        if (context instanceof ConfigurableListableBeanFactory blf) {
            this.beanFactory = blf;
            StructuralHotSwapManager manager = HotReloadAgent.getStructuralSwapManager();
            if (manager != null) {
                manager.addListener(this);
                if (classDir != null) {
                    manager.setClassDir(classDir);
                }
            }
            System.out.println("[SpringStructuralIntegration] Transparent structural Spring bridge active.");
        }
    }

    public void setClassDir(Path classDir) {
        this.classDir = classDir;
        StructuralHotSwapManager manager = HotReloadAgent.getStructuralSwapManager();
        if (manager != null) {
            manager.setClassDir(classDir);
        }
    }

    /**
     * BeanPostProcessor: after Spring creates a bean, wrap it in a HotSwapProxy
     * (if it has interfaces) and register the proxy for transparent updates.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean == null || beanFactory == null) {
            return bean;
        }

        Class<?> beanClass = bean.getClass();

        // Skip Spring infrastructure, proxies, and our own classes.
        if (beanClass.getName().startsWith("org.springframework.")
            || beanClass.getName().startsWith("com.example.hotreload.")
            || Proxy.isProxyClass(beanClass)) {
            return bean;
        }

        // Get all interfaces implemented by the bean.
        Class<?>[] interfaces = beanClass.getInterfaces();
        if (interfaces.length == 0) {
            // Cannot proxy a class without interfaces using JDK dynamic proxy.
            return bean;
        }

        // Store interfaces for potential re-proxying.
        beanInterfaces.put(beanName, interfaces);

        // Wrap in HotSwapProxy.
        HotSwapProxy proxy = HotSwapProxy.create(bean, interfaces);

        // Register with StructuralHotSwapManager for transparent updates.
        StructuralHotSwapManager manager = HotReloadAgent.getStructuralSwapManager();
        if (manager != null) {
            manager.registerProxy(beanName, proxy, beanClass.getName());
        }

        System.out.println("[SpringStructuralIntegration] Wrapped bean in HotSwapProxy: "
                + beanName + " -> " + beanClass.getName()
                + " (interfaces: " + String.join(", ",
                java.util.Arrays.stream(interfaces).map(Class::getSimpleName)
                .toArray(String[]::new)) + ")");

        return proxy.getProxy();
    }

    /**
     * Listener notified when a structural swap completes. The proxy registry
     * in StructuralHotSwapManager already updated all proxies, so this is
     * mostly for logging / Spring singleton bookkeeping.
     */
    @Override
    public void onStructuralSwapped(String className, Object newInstance,
                                    Class<?> newClazz, com.example.hotreload.structural.StructuralClassLoader classLoader) {
        if (beanFactory == null) {
            return;
        }

        // Find beans wrapped in proxies for this class and re-register as
        // singletons so Spring's getBean() returns the updated proxy.
        StructuralHotSwapManager manager = HotReloadAgent.getStructuralSwapManager();
        if (manager == null) {
            return;
        }

        for (Map.Entry<String, HotSwapProxy> entry : manager.getProxyRegistry().entrySet()) {
            String beanName = entry.getKey();
            HotSwapProxy proxy = entry.getValue();
            Object current = proxy.getCurrentInstance();

            if (current != null && current.getClass().getName().equals(className)) {
                // Re-register the (updated) proxy as the singleton.
                try {
                    org.springframework.beans.factory.support.DefaultListableBeanFactory dlbf =
                            (org.springframework.beans.factory.support.DefaultListableBeanFactory) beanFactory;
                    if (dlbf.containsSingleton(beanName)) {
                        dlbf.destroySingleton(beanName);
                    }
                    dlbf.registerSingleton(beanName, proxy.getProxy());
                    System.out.println("[SpringStructuralIntegration] Re-registered proxy bean: "
                            + beanName + " -> v" + manager.getVersion(className));
                } catch (BeansException e) {
                    // Bean may not be fully initialized yet; skip.
                }
            }
        }
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }
}
