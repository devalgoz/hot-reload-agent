package com.example.hotreload.structural;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * A dynamic proxy that provides transparent hot-swapping for a target object.
 *
 * Instead of holding a direct reference to a bean (which becomes stale after
 * a structural swap), clients hold a {@link HotSwapProxy}. Every method call
 * is forwarded to the {@code currentInstance}, which is updated by
 * {@link StructuralHotSwapManager} when a new class version is loaded.
 *
 * Usage:
 *   HotSwapProxy proxy = new HotSwapProxy(initialInstance);
 *   MyService service = (MyService) proxy.getProxy();
 *   // Later, after structural swap:
 *   proxy.setCurrentInstance(newInstance);
 *   service.newMethod(); // transparently calls the new version
 *
 * Limitations:
 *   - Only works for interface-based proxies (classes without interfaces need
 *     CGLIB/ByteBuddy subclass proxies, which is more complex)
 *   - Direct field access on the proxied object bypasses the proxy
 *   - Identity checks (==) compare the proxy, not the underlying instance
 */
public class HotSwapProxy implements InvocationHandler {

    private volatile Object currentInstance;
    private Object proxyObject;

    private HotSwapProxy(Object initialInstance) {
        this.currentInstance = initialInstance;
    }

    /**
     * Create a proxy for the given instance, implementing the given interfaces.
     * Returns the HotSwapProxy handler so callers can update the instance later.
     */
    public static HotSwapProxy create(Object initialInstance, Class<?>... interfaces) {
        HotSwapProxy handler = new HotSwapProxy(initialInstance);
        Object proxy = Proxy.newProxyInstance(
                HotSwapProxy.class.getClassLoader(),
                interfaces,
                handler);
        handler.proxyObject = proxy;
        return handler;
    }

    /**
     * Get the JDK dynamic proxy object. Clients pass this to Spring / other
     * frameworks instead of the raw bean instance.
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
    }

    /**
     * Get the current instance (for internal use by StructuralHotSwapManager).
     */
    public Object getCurrentInstance() {
        return currentInstance;
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
