package com.example.hotreload.spring;

import com.example.hotreload.HotReloadAgent;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Spring component that bridges the JVM agent with Spring.
 *
 * Registers {@link SpringBeanReloader} and {@link SpringConfigReloader} as
 * listeners on {@link HotReloadAgent} so that when a bean class or config
 * file changes on disk, the running context is updated without a full restart.
 *
 * Add this to your Spring Boot app's component scan and put the agent jar on
 * the classpath with -javaagent.
 */
@Component
public class SpringAgentIntegration implements ApplicationContextAware {

    private final SpringBeanReloader beanReloader = new SpringBeanReloader();
    private final SpringConfigReloader configReloader = new SpringConfigReloader();

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        if (context instanceof ConfigurableApplicationContext cac) {
            beanReloader.setContext(cac);
            configReloader.setEnvironment(cac.getEnvironment());

            HotReloadAgent.addReloadListener(beanReloader);
            HotReloadAgent.addReloadListener(configReloader);

            System.out.println("[SpringAgentIntegration] Spring hot-reload bridge active.");
        }
    }
}
