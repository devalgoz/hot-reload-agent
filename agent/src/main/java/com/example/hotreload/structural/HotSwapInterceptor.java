package com.example.hotreload.structural;

import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * ByteBuddy method-interception interceptor used by
 * {@link HotSwapProxy} subclass proxies.
 *
 * <p>Every intercepted method call on the generated subclass is forwarded to
 * the {@code currentInstance} field (set at proxy creation time and updated
 * on structural swap). This makes the subclass behave like a transparent
 * indirection layer, equivalent to a JDK dynamic proxy for interface-based
 * beans.</p>
 *
 * <p>Limitations inherited from ByteBuddy:</p>
 * <ul>
 *   <li>Final, static, native, and synchronized methods are not intercepted.</li>
 *   <li>Constructor calls are not intercepted.</li>
 * </ul>
 */
public class HotSwapInterceptor {

    /**
     * Intercept a method call on the subclass proxy and forward it to the
     * {@code currentInstance} field.
     *
     * @param <T>          return type of the intercepted method
     * @param superCall    call to the original superclass method (not used — we delegate to currentInstance)
     * @param method       the method being invoked
     * @param currentInstance the current target instance (from the proxy's field)
     * @param allArguments the arguments passed to the method
     * @return the result of invoking the method on {@code currentInstance}
     * @throws Throwable if the underlying method throws
     */
    @RuntimeType
    public static <T> T intercept(
            @SuperCall Callable<T> superCall,
            @Origin Method method,
            @AllArguments Object[] allArguments,
            Object currentInstance) throws Throwable {

        if (currentInstance == null) {
            throw new IllegalStateException("HotSwapProxy instance has been cleared");
        }

        try {
            java.lang.reflect.Method targetMethod = currentInstance.getClass()
                    .getMethod(method.getName(), method.getParameterTypes());
            targetMethod.setAccessible(true);
            return (T) targetMethod.invoke(currentInstance, allArguments);
        } catch (NoSuchMethodException e) {
            // Fall back to super call if method not found on current instance
            return superCall.call();
        }
    }
}
