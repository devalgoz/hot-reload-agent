package com.example.hotreload.spring;

import com.example.hotreload.HotReloadAgent;
import com.example.hotreload.jvmti.InstanceRegistry;
import com.example.hotreload.structural.HotSwapProxy;
import com.example.hotreload.structural.StructuralHotSwapManager;
import com.example.hotreload.structural.StructuralHotSwapManager.StructuralSwapListener;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring integration for transparent structural hot-swapping.
 *
 * Implements {@link BeanPostProcessor} so that after Spring creates any bean,
 * we wrap it in a {@link HotSwapProxy}. The proxy is registered with
 * {@link StructuralHotSwapManager} and returned to Spring instead of the raw
 * bean. All injection points receive the proxy.
 *
 * Two proxy strategies are used:
 *   <ul>
 *     <li><b>JDK dynamic proxy</b> — for beans that implement interfaces.</li>
 *     <li><b>ByteBuddy subclass proxy</b> — for beans without interfaces
 *       (uses {@link HotSwapProxy#create(Object, Class[])} with no interfaces).</li>
 *   </ul>
 *
 * On structural swap, {@link StructuralHotSwapManager} updates the proxy's
 * current instance. All existing proxy holders transparently see the new
 * version — achieving indirection-based transparency without JVMTI.
 *
 * Limitations:
 *   - Direct field access on the target object bypasses the proxy.
 *   - Identity checks (==) compare the proxy, not the underlying instance.
 *   - Final methods cannot be intercepted by ByteBuddy subclass proxies.
 *   - True heap-level transparency requires native JVMTI.
 */
@Component
public class SpringStructuralIntegration implements ApplicationContextAware, BeanPostProcessor, StructuralSwapListener {

    private ConfigurableListableBeanFactory beanFactory;
    private Path classDir;

    /** Tracks beanName -> interfaces (empty array means subclass proxy). */
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
     * (JDK dynamic proxy if interfaces exist, ByteBuddy subclass proxy
     * otherwise) and register the proxy for transparent updates.
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

        // Skip beans that are already proxies (avoid double-wrapping).
        if (bean instanceof com.example.hotreload.structural.HotSwapProxy) {
            return bean;
        }

        // Get all interfaces implemented by the bean.
        Class<?>[] interfaces = beanClass.getInterfaces();

        // Store proxy type info for potential re-proxying.
        beanInterfaces.put(beanName, interfaces);

        // Wrap in HotSwapProxy — uses JDK dynamic proxy if interfaces exist,
        // ByteBuddy subclass proxy otherwise.
        HotSwapProxy proxy = HotSwapProxy.create(bean, interfaces);

        // Track the raw bean instance for JVMTI heap migration.
        // This ensures direct field references to the bean see migrated values.
        InstanceRegistry.trackInstance(bean);

        // Register with StructuralHotSwapManager for transparent updates.
        StructuralHotSwapManager manager = HotReloadAgent.getStructuralSwapManager();
        if (manager != null) {
            manager.registerProxy(beanName, proxy, beanClass.getName());
        }

        String proxyType = proxy.isSubclassProxy() ? "ByteBuddy subclass" : "JDK dynamic";
        System.out.println("[SpringStructuralIntegration] Wrapped bean in " + proxyType
                + " HotSwapProxy: " + beanName + " -> " + beanClass.getName()
                + (interfaces.length > 0
                ? " (interfaces: " + String.join(", ",
                Arrays.stream(interfaces).map(Class::getSimpleName).toArray(String[]::new)) + ")"
                : " (no interfaces — subclass proxy)"));

        return proxy.getProxy();
    }

    /**
     * Listener notified when a structural swap completes. The proxy registry
     * in StructuralHotSwapManager already updated all proxies, so this is
     * mostly for Spring singleton bookkeeping (re-register updated proxy).
     */
    @Override
    public void onStructuralSwapped(String className, Object newInstance,
                                    Class<?> newClazz, com.example.hotreload.structural.StructuralClassLoader classLoader) {
        if (beanFactory == null) {
            return;
        }

        StructuralHotSwapManager manager = HotReloadAgent.getStructuralSwapManager();
        if (manager == null) {
            return;
        }

        for (Map.Entry<String, HotSwapProxy> entry : manager.getProxyRegistry().entrySet()) {
            String beanName = entry.getKey();
            HotSwapProxy proxy = entry.getValue();
            Object current = proxy.getCurrentInstance();

            if (current != null && current.getClass().getName().equals(className)) {
                // Re-register the (updated) proxy as the singleton so that
                // Spring's getBean() returns the updated proxy.
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
