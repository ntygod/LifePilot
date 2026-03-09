package com.lifepilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.event.EventListener;

/**
 * ZhiWei 应用启动类。
 *
 * <p>本地运行的个人 AI Agent 助手，通过 {@code java -jar zhiwei.jar} 一键启动。
 *
 * <p>Web Controller 通过组件扫描自动注册，依赖缺失时会启动失败（fail-fast），
 * 这比条件注册更清晰。A2A Server Controller 仍通过 AutoConfiguration 条件注册。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.lifepilot",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = {
                        "com\\.lifepilot\\.a2a\\.server\\.(AgentCardController|A2aTaskController|A2aMessageController)",
                        ".*Test\\$.*Config"
                }
        )
)
public class LifePilotApplication {

    private static final Logger log = LoggerFactory.getLogger(LifePilotApplication.class);

    public static void main(String[] args) {
        // Windows 控制台默认编码可能不是 UTF-8，显式设置避免中文输入丢字
        System.setProperty("stdout.encoding", "UTF-8");
        System.setProperty("stderr.encoding", "UTF-8");
        System.setProperty("stdin.encoding", "UTF-8");

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
