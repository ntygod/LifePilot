package com.lifepilot;

import java.util.HashMap;
import java.util.Map;

import com.lifepilot.app.LaunchMode;
import com.lifepilot.interaction.cli.FastPathRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.MapPropertySource;

/**
 * LifePilot 应用启动类。
 *
 * <p>本地运行的个人 AI Agent 助手，通过 {@code java -jar lifepilot.jar} 一键启动。
 *
 * <p>Web Controller 和 A2A Server Controller 通过各自的 AutoConfiguration 条件注册，
 * 此处排除组件扫描以避免无条件实例化导致依赖缺失。</p>
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
                        "com\\.lifepilot\\.interaction\\.web\\.controller\\..*",
                        "com\\.lifepilot\\.a2a\\.server\\.(AgentCardController|A2aTaskController|A2aMessageController)"
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

        if (FastPathRunner.tryFastPath(args)) {
            System.exit(0);
        }

        // 解析启动模式并注入对应 Spring 属性
        LaunchMode mode = FastPathRunner.resolveMode(args);

        SpringApplication app = new SpringApplication(LifePilotApplication.class);
        Map<String, Object> props = new HashMap<>();

        switch (mode) {
            case CLI -> props.put("spring.main.web-application-type", "none");
            case WEB -> {
                props.put("lifepilot.cli.enabled", false);
                props.put("lifepilot.gateway.channels.web.enabled", true);
            }
            case TRAY -> {
                props.put("lifepilot.cli.enabled", false);
                props.put("lifepilot.tray.enabled", true);
                props.put("lifepilot.gateway.channels.web.enabled", true);
            }
            case FULL -> props.put("lifepilot.gateway.channels.web.enabled", true);
        }

        props.put("lifepilot.app.launch-mode", mode.name().toLowerCase());

        // 使用 Initializer 注入属性，优先级高于 application.yml，
        // 确保启动模式的属性覆盖 YAML 中的默认值
        app.addInitializers(ctx -> {
            var source = new MapPropertySource("launchModeProperties", props);
            ctx.getEnvironment().getPropertySources().addFirst(source);
        });
        app.run(args);
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
