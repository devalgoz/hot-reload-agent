package com.example.hotreload;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} that traces method entry/exit.
 *
 * This is the bytecode-level interceptor that gets woven into target
 * classes by {@link ByteBuddyAgentTransformer}. It is intentionally
 * defensive (suppresses throwable) so a problem in tracing never crashes
 * the instrumented application.
 */
public class TracingAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Origin Class<?> declaringClass,
                               @Advice.Origin String method,
                               @Advice.AllArguments Object[] arguments) {
        System.out.printf("[ByteBuddy] ENTER %s.%s%n",
                declaringClass.getSimpleName(), method);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Origin Class<?> declaringClass,
                              @Advice.Origin String method) {
        System.out.printf("[ByteBuddy] EXIT  %s.%s%n",
                declaringClass.getSimpleName(), method);
    }
}
