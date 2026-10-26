package com.example.hotreload.structural;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * Factory for creating hot-swappable proxies. Supports two proxy types:
 *
 * <ul>
 *   <li><b>JDK dynamic proxy</b> — for classes that implement at least one
 *       interface. All method calls on the proxy interfaces are forwarded to
 *       the {@code currentInstance}.</li>
 *   <li><b>ByteBuddy subclass proxy</b> — for classes without interfaces.
 *       ByteBuddy generates a subclass at runtime that overrides all
 *       non-final, non-static methods and forwards them to the
 *       {@code currentInstance} via a {@link HotSwapInterceptor}.</li>
 * </ul>
 *
 * Usage:
 *   <pre>
 *   // Interface-based bean
 *   HotSwapProxy proxy = HotSwapProxy.create(myService, MyService.class);
 *   MyService service = (MyService) proxy.getProxy();
 *
 *   // Class-based bean (no interfaces)
 *   HotSwapProxy proxy = HotSwapProxy.create(myService);
 *   MyService service = (MyService) proxy.getProxy(); // proxy is a subclass
 *   </pre>
 *
 * After a structural swap, call {@link #setCurrentInstance(Object)} to make
 * all proxy holders transparently see the new version.
 *
 * Remaining limitations:
 *   <ul>
 *     <li>Direct field access on the proxied object bypasses the proxy.</li>
 *     <li>Identity checks ({@code ==}) compare the proxy, not the underlying instance.</li>
 *     <li>Final methods cannot be intercepted (ByteBuddy limitation).</li>
 *     <li>True heap-level transparency requires native JVMTI.</li>
 *   </ul>
 */
public class HotSwapProxy implements InvocationHandler {

    private volatile Object currentInstance;
    private Object proxyObject;
    private boolean subclassProxy;

    private HotSwapProxy(Object initialInstance, boolean subclassProxy) {
        this.currentInstance = initialInstance;
        this.subclassProxy = subclassProxy;
    }

    /**
     * Create a proxy for the given instance.
     *
     * <p>If {@code interfaces} is provided and non-empty, a JDK dynamic proxy
     * is created implementing those interfaces. Otherwise, a ByteBuddy
     * subclass of the instance's class is generated at runtime.</p>
     *
     * @param initialInstance the object to proxy
     * @param interfaces      interfaces to implement (may be empty for subclass proxy)
     * @return the HotSwapProxy handler
     */
    public static HotSwapProxy create(Object initialInstance, Class<?>... interfaces) {
        HotSwapProxy handler = new HotSwapProxy(initialInstance, interfaces.length == 0);

        if (interfaces.length > 0) {
            // JDK dynamic proxy — interface-based
            Object proxy = Proxy.newProxyInstance(
                    HotSwapProxy.class.getClassLoader(),
                    interfaces,
                    handler);
            handler.proxyObject = proxy;
        } else {
            // ByteBuddy subclass proxy — class-based
            Class<?> clazz = initialInstance.getClass();
            try {
                Class<?> proxyClass = new ByteBuddy()
                        .subclass(clazz)
                        .defineField("currentInstance", Object.class)
                        .defineMethod("getCurrentInstance", Object.class)
                        .intercept(MethodDelegation.to(HotSwapInterceptor.class))
                        .defineMethod("setCurrentInstance", void.class)
                        .withParameter(Object.class)
                        .intercept(MethodDelegation.to(HotSwapInterceptor.class))
                        .method(ElementMatchers.not(ElementMatchers.isFinal())
                                .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                .and(ElementMatchers.not(ElementMatchers.isNative()))
                                .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                                .and(ElementMatchers.not(ElementMatchers.isSynchronized()))
                                .and(ElementMatchers.not(ElementMatchers.named("equals")))
                                .and(ElementMatchers.not(ElementMatchers.named("hashCode")))
                                .and(ElementMatchers.not(ElementMatchers.named("toString"))))
                        .intercept(MethodDelegation.to(HotSwapInterceptor.class))
                        .make()
                        .load(clazz.getClassLoader())
                        .getLoaded();

                Object proxy = proxyClass.getDeclaredConstructor().newInstance();
                // Set the currentInstance field on the subclass instance
                java.lang.reflect.Field f = proxyClass.getDeclaredField("currentInstance");
                f.setAccessible(true);
                f.set(proxy, initialInstance);
                handler.proxyObject = proxy;
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Cannot create ByteBuddy subclass proxy for " + clazz.getName(), e);
            }
        }

        return handler;
    }

    /**
     * Get the proxy object. Clients pass this to Spring / other frameworks
     * instead of the raw bean instance.
     */
    public Object getProxy() {
        if (proxyObject == null) {
            throw new IllegalStateException("Proxy not yet created. Use create() first.");
        }
        return proxyObject;
    }

    /**
     * Update the current instance to a new version (e.g. after structural
     * hot-swap). All subsequent proxy calls go to the new instance.
     */
    public void setCurrentInstance(Object newInstance) {
        this.currentInstance = newInstance;
        // For subclass proxies, also update the field on the proxy instance itself
        if (subclassProxy && proxyObject != null) {
            try {
                java.lang.reflect.Field f = proxyObject.getClass().getDeclaredField("currentInstance");
                f.setAccessible(true);
                f.set(proxyObject, newInstance);
            } catch (Exception e) {
                // Ignore — the JDK dynamic proxy path uses this.handler.currentInstance
            }
        }
    }

    /**
     * Get the current instance (for internal use by StructuralHotSwapManager).
     */
    public Object getCurrentInstance() {
        return currentInstance;
    }

    /**
     * Whether this proxy uses a ByteBuddy subclass (true) or JDK dynamic
     * proxy (false).
     */
    public boolean isSubclassProxy() {
        return subclassProxy;
    }

    @Override
    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        if (currentInstance == null) {
            throw new IllegalStateException("HotSwapProxy instance has been cleared");
        }
        return method.invoke(currentInstance, args);
    }
}
