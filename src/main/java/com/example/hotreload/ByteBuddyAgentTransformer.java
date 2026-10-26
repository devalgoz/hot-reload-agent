package com.example.hotreload;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * Installs a ByteBuddy {@link AgentBuilder} on the JVM {@link Instrumentation}.
 *
 * The AgentBuilder registers its own {@link java.lang.instrument.ClassFileTransformer}
 * internally, so every class that is loaded (and, with RETRANSFORMATION strategy,
 * every class already loaded) is intercepted:
 *
 *   - JDK / agent classes are excluded so the runtime itself is not broken.
 *   - All public methods of application classes get the {@link TracingAdvice}
 *     woven in (method entry/exit logging).
 *
 * This is the real "bytecode transformer" half of a JRebel-like agent.
 */
public class ByteBuddyAgentTransformer {

    public static void install(Instrumentation instrumentation) {
        new AgentBuilder.Default()
            // Retransform classes that are already loaded when the agent attaches.
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            // Ignore the JDK and the agent itself.
            .type(ElementMatchers.not(ElementMatchers.nameStartsWith("java."))
                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax.")))
                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("jdk.")))
                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("com.example.hotreload"))))
            // Weave tracing advice into every public method.
            .transform((Builder<?> builder, TypeDescription typeDescription,
                        ClassLoader classLoader, JavaModule module,
                        ProtectionDomain protectionDomain) ->
                builder.method(ElementMatchers.isPublic())
                       .intercept(net.bytebuddy.asm.Advice.to(TracingAdvice.class))
            )
            .installOn(instrumentation);

        System.out.println("[ByteBuddyAgentTransformer] ByteBuddy agent builder installed.");
    }
}
