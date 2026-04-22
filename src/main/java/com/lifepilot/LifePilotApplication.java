package com.lifepilot;

import com.lifepilot.tool.config.ToolConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.event.EventListener;

import java.nio.charset.StandardCharsets;

/**
 * ZhiWei 应用启动类。
 *
 * <p>本地运行的个人 AI Agent 助手，通过 {@code java -jar zhiwei.jar} 一键启动。
 *
 * <p>Web Controller 通过组件扫描自动注册，依赖缺失时 fail-fast 更清晰；
 * A2A Server Controller 由 {@code A2aAutoConfiguration} 条件注册，下方 regex
 * 将其排除在扫描之外以避免重复注册。
 *
 * @author zsg
 * @since 2026-02-24
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.lifepilot",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                // A2A Server 所有 Controller（改由 A2aAutoConfiguration 条件注册）
                "com\\.lifepilot\\.a2a\\.server\\..*Controller",
                // 测试类中声明的内部 @Configuration 不污染主上下文
                ".*Test\\$.*Config"
        })
)
// 无条件注册 ToolConfigProperties：permission 等通过 @ComponentScan 注册的 Bean 会
// 构造注入它；即使 test profile 下 lifepilot.tool.enabled=false 关闭 ToolAutoConfiguration，
// 仍需读取其 enabled 字段来判断是否启用 tool。
@EnableConfigurationProperties(ToolConfigProperties.class)
public class LifePilotApplication {

    private static final Logger log = LoggerFactory.getLogger(LifePilotApplication.class);

    public static void main(String[] args) {
        configureConsoleEncoding();
        SpringApplication.run(LifePilotApplication.class, args);
    }

    /** Windows 控制台默认编码常为 GBK，显式设为 UTF-8 避免中文丢字。 */
    private static void configureConsoleEncoding() {
        var utf8 = StandardCharsets.UTF_8.name();
        System.setProperty("stdout.encoding", utf8);
        System.setProperty("stderr.encoding", utf8);
        System.setProperty("stdin.encoding", utf8);
    }

    /** 启动完成后打印应用名与版本，便于日志定位。 */
    @EventListener
    public void onApplicationStarted(ApplicationStartedEvent event) {
        var env = event.getApplicationContext().getEnvironment();
        var appName = env.getProperty("spring.application.name", "lifepilot");
        var version = getClass().getPackage().getImplementationVersion();
        log.info("应用启动完成: name={}, version={}", appName, version != null ? version : "dev");
    }
}
