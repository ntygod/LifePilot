package com.lifepilot.interaction.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * 启动时从 SQLite 数据库读取渠道 enabled 开关，注入到 Spring Environment。
 *
 * <p>在 application.yml 之上叠加一层高优先级属性源，使 Web UI 中保存的
 * 渠道开关在重启后能被 {@code @ConditionalOnProperty} 正确读取。
 * 仅覆盖 {@code lifepilot.gateway.channels.{channel}.enabled} 属性。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class ChannelEnabledEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 解析数据库路径（与 application.yml 中 spring.datasource.url 保持一致）
        String dbPath = resolveDbPath(environment);
        if (dbPath == null || !Files.exists(Path.of(dbPath))) {
            return;
        }

        Map<String, Object> channelEnabled = readChannelEnabledFromDb(dbPath);
        if (channelEnabled.isEmpty()) {
            return;
        }

        // 添加高优先级属性源（优先于 application.yml）
        environment.getPropertySources().addFirst(
                new MapPropertySource("channelEnabledFromDb", channelEnabled));
    }

    /**
     * 从 Spring Environment 解析 SQLite 数据库文件路径。
     */
    private String resolveDbPath(ConfigurableEnvironment environment) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            return null;
        }
        // jdbc:sqlite:/path/to/db?params → /path/to/db
        String path = url.substring("jdbc:sqlite:".length());
        int qIdx = path.indexOf('?');
        if (qIdx > 0) {
            path = qIdx > 0 ? path.substring(0, qIdx) : path;
        }
        // 替换 ${user.home}
        path = path.replace("${user.home}", System.getProperty("user.home"));
        return path;
    }

    /**
     * 直接通过 JDBC 读取 user_settings.channel_config_json，提取各渠道 enabled 标志。
     */
    private Map<String, Object> readChannelEnabledFromDb(String dbPath) {
        Map<String, Object> props = new HashMap<>();
        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT channel_config_json FROM user_settings WHERE id = 'default'")) {

            if (rs.next()) {
                String json = rs.getString("channel_config_json");
                if (json != null && !json.isBlank() && !"{}".equals(json)) {
                    Map<String, Object> config = MAPPER.readValue(json, new TypeReference<>() {});
                    extractEnabled(config, "feishu", props);
                    extractEnabled(config, "wecom", props);
                    extractEnabled(config, "dingtalk", props);
                }
            }
        } catch (Exception ignored) {
            // 数据库不存在、表不存在、列不存在等情况均静默跳过
            // 首次启动时 Flyway 尚未执行，此处失败是正常的
        }
        return props;
    }

    private void extractEnabled(Map<String, Object> config, String channel, Map<String, Object> props) {
        Object channelObj = config.get(channel);
        if (channelObj instanceof Map<?, ?> m) {
            Object enabled = m.get("enabled");
            if (enabled instanceof Boolean b) {
                props.put("lifepilot.gateway.channels." + channel + ".enabled", b.toString());
            }
        }
    }
}
