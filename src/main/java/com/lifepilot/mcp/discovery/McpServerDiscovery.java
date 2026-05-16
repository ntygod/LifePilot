package com.lifepilot.mcp.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lifepilot.mcp.config.McpConfigProperties;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.protocol.McpJsonSupport;
import com.lifepilot.mcp.transport.TransportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MCP 服务器自动发现组件 — 扫描本地配置文件自动注册 MCP 服务器。
 *
 * <p>发现路径（按优先级从高到低）：</p>
 * <ol>
 *   <li>{@code ~/.zhiwei/mcp/servers.json} — 知微内置 + 用户自定义（统一管理）</li>
 *   <li>{@code ~/.mcp/servers.json} — 用户全局 MCP 配置</li>
 *   <li>{@code {project-root}/.mcp.json} — 项目本地配置</li>
 *   <li>自定义路径 — 通过 {@code lifepilot.mcp.discovery.paths} 配置</li>
 * </ol>
 *
 * <p>启动时自动将 classpath 内置的 MCP 服务器配置释放到 {@code ~/.zhiwei/mcp/servers.json}，
 * 仅补充新增的内置服务器，不覆盖用户已有配置。用户可通过 Web UI 管理所有 MCP 服务器。</p>
 *
 * <p>合并策略：显式配置（application.yml）优先，同名 server 显式覆盖发现。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class McpServerDiscovery {

    private static final Logger log = LoggerFactory.getLogger(McpServerDiscovery.class);

    /** classpath 内置 MCP 服务器配置路径。 */
    private static final String BUILTIN_MCP_RESOURCE = "mcp/servers.json";

    private final McpConfigProperties properties;

    public McpServerDiscovery(McpConfigProperties properties) {
        this.properties = properties;
    }

    /**
     * 释放内置 MCP 服务器配置到用户目录，然后执行自动发现。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>如果 seedBuiltinServers=true，将 classpath 内置配置合并到 ~/.zhiwei/mcp/servers.json</li>
     *   <li>扫描所有发现路径，收集 MCP 服务器配置</li>
     *   <li>显式配置优先于发现配置，同名 server 显式覆盖发现</li>
     * </ol>
     *
     * @return 合并后的配置列表（显式 + 发现去重）
     */
    public List<McpServerConfig> discover() {
        if (!properties.getDiscovery().isEnabled()) {
            log.debug("MCP 自动发现已禁用");
            return properties.toServerConfigs();
        }

        // 释放内置 MCP 配置到用户目录
        if (properties.getDiscovery().isSeedBuiltinServers()) {
            seedBuiltinServers();
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
            log.info("MCP 自动发现: 发现 {} 个服务器配置", discoveredConfigs.size());
        }

        // 合并：显式 + 发现
        var merged = new ArrayList<>(explicitConfigs);
        merged.addAll(discoveredConfigs.values());
        return List.copyOf(merged);
    }

    /**
     * 将 classpath 内置 MCP 服务器配置合并到用户目录。
     *
     * <p>仅补充新增的内置服务器，不覆盖用户已有配置。
     * 用户删除的服务器不会被重新添加（通过 _removed 标记判断，预留扩展）。</p>
     */
    @SuppressWarnings("unchecked")
    void seedBuiltinServers() {
        var userMcpDir = getUserMcpDir();
        var userServersFile = userMcpDir.resolve("servers.json");

        try {
            // 读取 classpath 内置配置
            Map<String, Object> builtinRoot;
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(BUILTIN_MCP_RESOURCE)) {
                if (is == null) {
                    log.debug("未找到内置 MCP 配置: {}", BUILTIN_MCP_RESOURCE);
                    return;
                }
                builtinRoot = McpJsonSupport.MAPPER.readValue(is, new TypeReference<>() {});
            }

            var builtinServers = (Map<String, Object>) builtinRoot.getOrDefault("mcpServers", Map.of());
            if (builtinServers.isEmpty()) {
                return;
            }

            // 读取用户已有配置（如果存在）
            Map<String, Object> userRoot;
            Map<String, Object> userServers;
            if (Files.exists(userServersFile)) {
                var content = Files.readString(userServersFile);
                userRoot = McpJsonSupport.MAPPER.readValue(content, new TypeReference<>() {});
                userServers = (Map<String, Object>) userRoot.getOrDefault("mcpServers", new LinkedHashMap<>());
            } else {
                userRoot = new LinkedHashMap<>();
                userServers = new LinkedHashMap<>();
            }

            // 合并：仅补充用户配置中不存在的内置服务器
            int added = 0;
            for (var entry : builtinServers.entrySet()) {
                if (!userServers.containsKey(entry.getKey())) {
                    userServers.put(entry.getKey(), entry.getValue());
                    added++;
                }
            }

            if (added > 0) {
                // 写回用户配置
                userRoot.put("mcpServers", userServers);
                Files.createDirectories(userMcpDir);
                McpJsonSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValue(userServersFile.toFile(), userRoot);
                log.info("内置 MCP 配置已释放: 新增 {} 个服务器到 {}", added, userServersFile);
            }
        } catch (IOException e) {
            log.warn("释放内置 MCP 配置失败: {}", e.getMessage());
        }
    }

    /**
     * 获取用户 MCP 配置目录路径。
     */
    Path getUserMcpDir() {
        return Path.of(System.getProperty("user.home"), "zhiwei", "mcp");
    }

    /**
     * 构建发现路径列表。
     */
    private List<Path> buildDiscoveryPaths() {
        var paths = new ArrayList<Path>();

        // 知微内置 + 用户自定义（最高优先级）
        paths.add(getUserMcpDir().resolve("servers.json"));

        // 用户全局 MCP 配置
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
            var root = McpJsonSupport.MAPPER.readValue(content,
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

        // 解析 autoConnect（默认 true）
        var autoConnect = def.containsKey("autoConnect")
                ? Boolean.parseBoolean(def.get("autoConnect").toString())
                : true;

        // 解析 idleTimeout（秒数或 ISO-8601 Duration 字符串，缺省走默认 10 分钟）
        Duration idleTimeout = null;
        if (def.containsKey("idleTimeout")) {
            var raw = def.get("idleTimeout").toString();
            try {
                idleTimeout = Duration.ofSeconds(Long.parseLong(raw));
            } catch (NumberFormatException _) {
                idleTimeout = Duration.parse(raw);
            }
        }

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
                .autoConnect(autoConnect)
                .reconnect(autoConnect)
                .idleTimeout(idleTimeout)
                .build();
    }
}
