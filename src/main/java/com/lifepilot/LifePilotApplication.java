package com.lifepilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;

/**
 * LifePilot 应用启动类。
 *
 * <p>本地运行的个人 AI Agent 助手，通过 {@code java -jar lifepilot.jar} 一键启动。
 *
 * @author zsg
 * @since 2026-02-24
 */
@SpringBootApplication
public class LifePilotApplication {

    private static final Logger log = LoggerFactory.getLogger(LifePilotApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(LifePilotApplication.class, args);
    }

    /**
     * 应用启动完成后输出名称和版本信息。
     *
     * @param event 应用启动事件
     */
    @EventListener
    public void onApplicationStarted(ApplicationStartedEvent event) {
        var appName = event.getApplicationContext().getEnvironment()
                .getProperty("spring.application.name", "lifepilot");
        var version = getClass().getPackage().getImplementationVersion();
        log.info("应用启动完成: name={}, version={}", appName,
                version != null ? version : "dev");
    }
}
