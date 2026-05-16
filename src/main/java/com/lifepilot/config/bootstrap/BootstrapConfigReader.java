package com.lifepilot.config.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 应用启动引导配置读取器。
 *
 * <p>在 Spring Environment 初始化阶段读取 {@code ~/zhiwei/config.json}，
 * 将其中的 {@code home} 映射到 {@code zhiwei.home} 属性，
 * 将其中的 {@code workspace} 映射到 {@code zhiwei.workspace} 属性，
 * 使用户可以通过前端设置页面修改 HOME 和 WORKSPACE 目录。</p>
 *
 * <p>优先级：命令行参数 &gt; config.json &gt; application.yml 默认值。</p>
 *
 * @author zsg
 * @since 2026-04-12
 */
public class BootstrapConfigReader implements EnvironmentPostProcessor {

    /** 固定配置文件路径：{user.home}/zhiwei/config.json */
    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"), "zhiwei", "config.json");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            var mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(CONFIG_PATH.toFile());
            Map<String, Object> properties = new HashMap<>();

            // 读取 home 字段映射到 zhiwei.home
            JsonNode homeNode = root.get("home");
            if (homeNode != null && homeNode.isTextual() && !homeNode.asText().isBlank()) {
                properties.put("zhiwei.home", homeNode.asText());
            }

            // 读取 workspace 字段映射到 zhiwei.workspace
            JsonNode workspaceNode = root.get("workspace");
            if (workspaceNode != null && workspaceNode.isTextual() && !workspaceNode.asText().isBlank()) {
                properties.put("zhiwei.workspace", workspaceNode.asText());
            }

            if (!properties.isEmpty()) {
                environment.getPropertySources()
                        .addAfter("systemProperties", new MapPropertySource("zhiwei-bootstrap", properties));
            }
        } catch (IOException ignored) {
            // 配置文件解析失败时静默跳过，使用默认值
        }
    }
}
