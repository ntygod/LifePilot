package com.lifepilot.mcp.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.mcp.config.McpConfigProperties;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.transport.TransportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MCP 服务器自动发现组件 — 扫描本地配置文件自动注册 MCP 服务器。
 *
 * <p>发现路径（按优先级从高到低）：</p>
 * <ol>
 *   <li>{@code ~/.mcp/servers.json} — 用户全局配置</li>
 *   <li>{@code {project-root}/.mcp.json} — 项目本地配置</li>
 *   <li>自定义路径 — 通过 {@code lifepilot.mcp.discovery.paths} 配置</li>
 * </ol>
 *
 * <p>合并策略：显式配置（application.yml）优先，同名 server 显式覆盖发现。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class McpServerDiscovery {

    private static final Logger log = LoggerFactory.getLogger(McpServerDiscovery.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpConfigProperties properties;

    public McpServerDiscovery(McpConfigProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行自动发现，返回合并后的 MCP 服务器配置列表。
     *
     * <p>显式配置优先于发现配置，同名 server 显式覆盖发现。</p>
     *
     * @return 合并后的配置列表（显式 + 发现去重）
     */
    public List<McpServerConfig> discover() {
        if (!properties.getDiscovery().isEnabled()) {
            log.debug("MCP 自动发现已禁用");
            return properties.toServerConfigs();
        }

        // 收集显式配置的 server 名称
        var explicitConfigs = properties.toServerConfigs();
        var explicitNames = new HashSet<String>();
        for (var config : explicitConfigs) {
            explicitNames.add(config.name());
        }

        // 扫描发现路径
        var discoveredConfigs = new LinkedHashMap<String, McpServerConfig>();
        var discoveryPaths = buildDiscoveryPaths();

        for (var path : discoveryPaths) {
            var configs = parseConfigFile(path);
            for (var config : configs) {
                // 显式配置优先，同名跳过；先发现的优先
                if (!explicitNames.contains(config.name())
                        && !discoveredConfigs.containsKey(config.name())) {
                    discoveredConfigs.put(config.name(), config);
                }
            }
        }

        if (!discoveredConfigs.isEmpty()) {
            log.info("MCP 自动发现: 发现 {} 个新服务器配置", discoveredConfigs.size());
        }

        // 合并：显式 + 发现
        var merged = new ArrayList<>(explicitConfigs);
        merged.addAll(discoveredConfigs.values());
        return List.copyOf(merged);
    }

    /**
     * 构建发现路径列表。
     */
    private List<Path> buildDiscoveryPaths() {
        var paths = new ArrayList<Path>();

        // 用户全局配置
        var userHome = System.getProperty("user.home");
        if (userHome != null) {
            paths.add(Path.of(userHome, ".mcp", "servers.json"));
        }

        // 项目本地配置
        var projectRoot = System.getProperty("user.dir");
        if (projectRoot != null) {
            paths.add(Path.of(projectRoot, ".mcp.json"));
        }

        // 自定义路径
        for (var customPath : properties.getDiscovery().getPaths()) {
            paths.add(Path.of(customPath));
        }

        return paths;
    }

    /**
     * 解析单个配置文件，返回 McpServerConfig 列表。
     *
     * <p>路径不存在或 JSON 无效时 log.debug 跳过，不影响其他发现源。</p>
     */
    @SuppressWarnings("unchecked")
    private List<McpServerConfig> parseConfigFile(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            log.debug("MCP 发现路径不存在: {}", path);
            return List.of();
        }

        try {
            var content = Files.readString(path);
            var root = MAPPER.readValue(content,
                    new TypeReference<Map<String, Object>>() {});

            var mcpServers = (Map<String, Object>) root.get("mcpServers");
            if (mcpServers == null || mcpServers.isEmpty()) {
                log.debug("MCP 配置文件无 mcpServers 节点: {}", path);
                return List.of();
            }

            var configs = new ArrayList<McpServerConfig>();
            for (var entry : mcpServers.entrySet()) {
                try {
                    var serverName = entry.getKey();
                    var serverDef = (Map<String, Object>) entry.getValue();
                    var config = parseServerEntry(serverName, serverDef);
                    configs.add(config);
                } catch (Exception e) {
                    log.debug("MCP 发现: 解析 server {} 失败: {}", entry.getKey(), e.getMessage());
                }
            }

            log.debug("MCP 发现: 从 {} 解析到 {} 个服务器", path, configs.size());
            return configs;
        } catch (IOException e) {
            log.debug("MCP 发现: 读取配置文件失败: {}, error={}", path, e.getMessage());
            return List.of();
        }
    }

    /**
     * 将单个 server JSON 定义解析为 McpServerConfig。
     */
    @SuppressWarnings("unchecked")
    private McpServerConfig parseServerEntry(String name, Map<String, Object> def) {
        var command = (String) def.get("command");
        var args = def.containsKey("args")
                ? ((List<Object>) def.get("args")).stream().map(Object::toString).toList()
                : List.<String>of();
        var env = def.containsKey("env")
                ? ((Map<String, Object>) def.get("env")).entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, e -> e.getValue().toString()))
                : Map.<String, String>of();
        var url = (String) def.get("url");

        // 推断传输类型
        var transportStr = (String) def.get("transport");
        TransportType transport;
        if (transportStr != null) {
            transport = TransportType.valueOf(transportStr.toUpperCase().replace("-", "_"));
        } else {
            transport = (command != null) ? TransportType.STDIO : TransportType.STREAMABLE_HTTP;
        }

        return McpServerConfig.builder()
                .name(name)
                .transport(transport)
                .command(command)
                .args(args)
                .url(url)
                .env(env)
                .autoConnect(true)
                .reconnect(true)
                .build();
    }
}
