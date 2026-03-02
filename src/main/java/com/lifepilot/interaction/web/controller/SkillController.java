package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.mcp.transport.TransportType;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.yaml.YamlSkillLoader;
import com.lifepilot.skill.yaml.YamlSkillSerializer;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill / MCP Server 管理 REST Controller。
 *
 * <p>提供 Skill 列表/详情/注销和 MCP Server 列表/连接/断开/工具查询端点。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
@RestController
@RequestMapping("/api")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillRegistry skillRegistry;
    private final McpServerRegistry mcpServerRegistry;
    private final DynamicToolRegistry toolRegistry;
    private final YamlSkillLoader yamlSkillLoader;
    private final YamlSkillSerializer yamlSkillSerializer;
    private final SkillConfigProperties skillConfig;
    private final Path skillsDirectory;

    public SkillController(SkillRegistry skillRegistry,
                           McpServerRegistry mcpServerRegistry,
                           DynamicToolRegistry toolRegistry,
                           YamlSkillLoader yamlSkillLoader,
                           YamlSkillSerializer yamlSkillSerializer,
                           SkillConfigProperties skillConfig) {
        this.skillRegistry = skillRegistry;
        this.mcpServerRegistry = mcpServerRegistry;
        this.toolRegistry = toolRegistry;
        this.yamlSkillLoader = yamlSkillLoader;
        this.yamlSkillSerializer = yamlSkillSerializer;
        this.skillConfig = skillConfig;
        this.skillsDirectory = Path.of(skillConfig.getDirectory());
    }

    // ── Skill 端点 ──────────────────────────────────────────

    /**
     * 获取所有已注册 Skill 列表。
     *
     * @return Skill 定义列表
     */
    @GetMapping("/skills")
    public ResponseEntity<?> listSkills() {
        log.debug("查询 Skill 列表");
        return ResponseEntity.ok(skillRegistry.listAll());
    }

    /**
     * 获取指定 Skill 详情。
     *
     * @param id Skill ID
     * @return Skill 定义，不存在返回 404
     */
    @GetMapping("/skills/{id}")
    public ResponseEntity<?> getSkill(@PathVariable String id) {
        return skillRegistry.find(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "Skill 不存在: id=" + id, Instant.now())));
    }

    /**
     * 创建 Skill。
     *
     * @param request 创建请求（包含 yamlContent 或完整 Skill 定义）
     * @return 201 创建成功，400 参数错误
     */
    @PostMapping("/skills")
    public ResponseEntity<?> createSkill(@RequestBody Map<String, Object> request) {
        log.debug("创建 Skill: request={}", request);
        
        try {
            // 获取 YAML 内容
            String yamlContent = getString(request, "yamlContent");
            if (yamlContent == null || yamlContent.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "yamlContent 不能为空", Instant.now()));
            }

            // 解析 YAML
            Path tempFile = Files.createTempFile("skill-", ".yaml");
            try {
                Files.writeString(tempFile, yamlContent);
                Optional<SkillDefinition> definitionOpt = yamlSkillLoader.loadFile(tempFile);
                
                if (definitionOpt.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(new ErrorResponse(400, "YAML 解析失败，请检查格式", Instant.now()));
                }

                SkillDefinition definition = definitionOpt.get();

                // 检查是否已存在
                if (skillRegistry.find(definition.id()).isPresent()) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new ErrorResponse(409, "Skill ID 已存在: " + definition.id(), Instant.now()));
                }

                // 保存到文件系统
                Path skillFile = skillsDirectory.resolve(definition.id() + ".yaml");
                if (!Files.exists(skillsDirectory)) {
                    Files.createDirectories(skillsDirectory);
                }
                Files.writeString(skillFile, yamlContent);

                // 注册到 SkillRegistry
                boolean registered = skillRegistry.register(definition);
                if (!registered) {
                    // 如果注册失败，删除文件
                    Files.deleteIfExists(skillFile);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(new ErrorResponse(400, "Skill 注册失败，请检查定义", Instant.now()));
                }

                log.info("Skill 创建成功: id={}, name={}", definition.id(), definition.name());
                return ResponseEntity.status(HttpStatus.CREATED).body(definition);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            log.error("创建 Skill 失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "创建失败: " + e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("创建 Skill 失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "创建失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 更新 Skill（仅 YAML 类型的 Skill 可编辑）。
     *
     * @param id Skill ID
     * @param request 更新请求（包含 yamlContent）
     * @return 200 更新成功，404 不存在，400 参数错误
     */
    @PutMapping("/skills/{id}")
    public ResponseEntity<?> updateSkill(@PathVariable String id,
                                         @RequestBody Map<String, Object> request) {
        log.debug("更新 Skill: id={}, request={}", id, request);
        
        // 检查 Skill 是否存在
        Optional<SkillDefinition> existingOpt = skillRegistry.find(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "Skill 不存在: id=" + id, Instant.now()));
        }

        SkillDefinition existing = existingOpt.get();

        // 检查是否为 YAML 类型（只有 YAML 类型的 Skill 可以更新）
        if (!(existing.source() instanceof SkillSource.UserDefined)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, "只能更新用户创建的 Skill（YAML 类型）", Instant.now()));
        }

        try {
            // 获取 YAML 内容
            String yamlContent = getString(request, "yamlContent");
            if (yamlContent == null || yamlContent.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "yamlContent 不能为空", Instant.now()));
            }

            // 解析 YAML
            Path tempFile = Files.createTempFile("skill-", ".yaml");
            try {
                Files.writeString(tempFile, yamlContent);
                Optional<SkillDefinition> definitionOpt = yamlSkillLoader.loadFile(tempFile);
                
                if (definitionOpt.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(new ErrorResponse(400, "YAML 解析失败，请检查格式", Instant.now()));
                }

                SkillDefinition definition = definitionOpt.get();

                // 检查 ID 是否匹配
                if (!definition.id().equals(id)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(new ErrorResponse(400, "YAML 中的 ID 必须与路径参数一致", Instant.now()));
                }

                // 更新文件系统
                Path skillFile = skillsDirectory.resolve(id + ".yaml");
                Files.writeString(skillFile, yamlContent);

                // 更新注册表
                boolean registered = skillRegistry.register(definition);
                if (!registered) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(new ErrorResponse(400, "Skill 更新失败，请检查定义", Instant.now()));
                }

                log.info("Skill 更新成功: id={}", id);
                return ResponseEntity.ok(definition);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            log.error("更新 Skill 失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "更新失败: " + e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("更新 Skill 失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "更新失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 注销指定 Skill（Builtin 类型不可注销）。
     *
     * @param id Skill ID
     * @return 204 成功，400 Builtin 不可注销，404 不存在
     */
    @DeleteMapping("/skills/{id}")
    public ResponseEntity<?> unregisterSkill(@PathVariable String id) {
        var skillOpt = skillRegistry.find(id);
        if (skillOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "Skill 不存在: id=" + id, Instant.now()));
        }

        // Builtin Skill 不可注销
        if (skillOpt.get().source() instanceof SkillSource.Builtin) {
            log.warn("尝试注销内置 Skill 被拒绝: id={}", id);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse(400, "内置 Skill 不可注销: id=" + id, Instant.now()));
        }

        skillRegistry.unregister(id);
        
        // 删除文件系统文件
        try {
            Path skillFile = skillsDirectory.resolve(id + ".yaml");
            Files.deleteIfExists(skillFile);
        } catch (IOException e) {
            log.warn("删除 Skill 文件失败: id={}, error={}", id, e.getMessage());
        }
        
        log.info("Skill 已注销: id={}", id);
        return ResponseEntity.noContent().build();
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    // ── MCP Server 端点 ─────────────────────────────────────

    /**
     * 获取所有 MCP Server 列表。
     *
     * @return MCP Server 条目列表
     */
    @GetMapping("/mcp/servers")
    public ResponseEntity<?> listMcpServers() {
        log.debug("查询 MCP Server 列表");
        return ResponseEntity.ok(mcpServerRegistry.listServers());
    }

    /**
     * 获取指定 MCP Server 详情。
     *
     * @param name Server 名称
     * @return Server 条目，不存在返回 404
     */
    @GetMapping("/mcp/servers/{name}")
    public ResponseEntity<?> getMcpServer(@PathVariable String name) {
        return mcpServerRegistry.getServer(name)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "MCP Server 不存在: name=" + name, Instant.now())));
    }

    /**
     * 连接指定 MCP Server。
     *
     * @param name Server 名称
     * @return 202 已接受，404 不存在
     */
    @PostMapping("/mcp/servers/{name}/connect")
    public ResponseEntity<?> connectMcpServer(@PathVariable String name) {
        var serverOpt = mcpServerRegistry.getServer(name);
        if (serverOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "MCP Server 不存在: name=" + name, Instant.now()));
        }

        mcpServerRegistry.connectServer(serverOpt.get().config());
        log.info("MCP Server 连接请求已接受: name={}", name);
        return ResponseEntity.accepted().build();
    }

    /**
     * 断开指定 MCP Server。
     *
     * @param name Server 名称
     * @return 204 成功，404 不存在
     */
    @PostMapping("/mcp/servers/{name}/disconnect")
    public ResponseEntity<?> disconnectMcpServer(@PathVariable String name) {
        var serverOpt = mcpServerRegistry.getServer(name);
        if (serverOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "MCP Server 不存在: name=" + name, Instant.now()));
        }

        mcpServerRegistry.disconnectServer(name);
        log.info("MCP Server 已断开: name={}", name);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取指定 MCP Server 的工具列表。
     *
     * @param name Server 名称
     * @return 工具列表，Server 不存在返回 404
     */
    @GetMapping("/mcp/servers/{name}/tools")
    public ResponseEntity<?> getMcpServerTools(@PathVariable String name) {
        var serverOpt = mcpServerRegistry.getServer(name);
        if (serverOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, "MCP Server 不存在: name=" + name, Instant.now()));
        }

        List<ToolContract> tools = toolRegistry.getToolsByServer(name);
        return ResponseEntity.ok(tools);
    }

    /**
     * 创建 MCP Server。
     *
     * @param request 创建请求（包含 name 和 config）
     * @return 201 创建成功，400 参数错误
     */
    @PostMapping("/mcp/servers")
    public ResponseEntity<?> createMcpServer(@RequestBody Map<String, Object> request) {
        log.debug("创建 MCP Server: request={}", request);
        
        try {
            String name = getString(request, "name");
            if (name == null || name.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new ErrorResponse(400, "name 不能为空", Instant.now()));
            }

            // 检查是否已存在
            if (mcpServerRegistry.getServer(name).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ErrorResponse(409, "MCP Server 已存在: name=" + name, Instant.now()));
            }

            // 构建配置
            McpServerConfig config = buildMcpServerConfig(name, request);
            
            // 初始化并连接
            mcpServerRegistry.initializeAll(List.of(config));
            if (config.autoConnect()) {
                mcpServerRegistry.connectServer(config);
            }

            log.info("MCP Server 创建成功: name={}", name);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(mcpServerRegistry.getServer(name).orElse(null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("创建 MCP Server 失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "创建失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 更新 MCP Server。
     *
     * @param name Server 名称
     * @param request 更新请求（包含 config）
     * @return 200 更新成功，404 不存在，400 参数错误
     */
    @PutMapping("/mcp/servers/{name}")
    public ResponseEntity<?> updateMcpServer(@PathVariable String name,
                                             @RequestBody Map<String, Object> request) {
        log.debug("更新 MCP Server: name={}, request={}", name, request);
        
        // 检查是否存在
        var existingOpt = mcpServerRegistry.getServer(name);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "MCP Server 不存在: name=" + name, Instant.now()));
        }

        try {
            // 先断开连接
            mcpServerRegistry.disconnectServer(name);

            // 构建新配置（保持名称不变）
            McpServerConfig config = buildMcpServerConfig(name, request);
            
            // 重新初始化并连接
            mcpServerRegistry.initializeAll(List.of(config));
            if (config.autoConnect()) {
                mcpServerRegistry.connectServer(config);
            }

            log.info("MCP Server 更新成功: name={}", name);
            return ResponseEntity.ok(mcpServerRegistry.getServer(name).orElse(null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("更新 MCP Server 失败: name={}", name, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "更新失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 删除 MCP Server。
     *
     * @param name Server 名称
     * @return 204 删除成功，404 不存在
     */
    @DeleteMapping("/mcp/servers/{name}")
    public ResponseEntity<?> deleteMcpServer(@PathVariable String name) {
        log.debug("删除 MCP Server: name={}", name);
        
        var serverOpt = mcpServerRegistry.getServer(name);
        if (serverOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "MCP Server 不存在: name=" + name, Instant.now()));
        }

        // 断开连接（这会注销工具）
        mcpServerRegistry.disconnectServer(name);
        
        // 注意：McpServerRegistry 没有公开的 remove 方法，但断开连接后工具已注销
        // 如果需要完全移除注册表条目，需要扩展 McpServerRegistry
        
        log.info("MCP Server 已删除: name={}", name);
        return ResponseEntity.noContent().build();
    }

    // ── MCP Server 辅助方法 ──────────────────────────────────────────

    private McpServerConfig buildMcpServerConfig(String name, Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> configMap = (Map<String, Object>) request.get("config");
        if (configMap == null) {
            throw new IllegalArgumentException("config 不能为空");
        }

        // 解析 transport
        String transportStr = getString(configMap, "transport");
        if (transportStr == null || transportStr.isBlank()) {
            throw new IllegalArgumentException("transport 不能为空");
        }
        TransportType transport = parseTransportType(transportStr);

        // 解析其他字段
        String command = getString(configMap, "command");
        @SuppressWarnings("unchecked")
        List<String> args = configMap.get("args") instanceof List<?> 
                ? (List<String>) configMap.get("args") 
                : new ArrayList<>();
        String url = getString(configMap, "url");
        @SuppressWarnings("unchecked")
        Map<String, String> env = configMap.get("env") instanceof Map<?, ?>
                ? (Map<String, String>) configMap.get("env")
                : Map.of();

        // 解析可选字段
        Duration timeout = parseDuration(configMap.get("timeout"), McpServerConfig.DEFAULT_TIMEOUT);
        boolean autoConnect = getBooleanOrDefault(configMap, "autoConnect", false);
        boolean reconnect = getBooleanOrDefault(configMap, "reconnect", true);
        Duration reconnectDelay = parseDuration(configMap.get("reconnectDelay"), 
                McpServerConfig.DEFAULT_RECONNECT_DELAY);
        int maxReconnectAttempts = getIntOrDefault(configMap, "maxReconnectAttempts", 
                McpServerConfig.DEFAULT_MAX_RECONNECT_ATTEMPTS);
        Duration healthCheckInterval = parseDuration(configMap.get("healthCheckInterval"), 
                McpServerConfig.DEFAULT_HEALTH_CHECK_INTERVAL);

        return McpServerConfig.builder()
                .name(name)
                .transport(transport)
                .command(command)
                .args(args)
                .url(url)
                .env(env)
                .timeout(timeout)
                .autoConnect(autoConnect)
                .reconnect(reconnect)
                .reconnectDelay(reconnectDelay)
                .maxReconnectAttempts(maxReconnectAttempts)
                .healthCheckInterval(healthCheckInterval)
                .build();
    }

    private TransportType parseTransportType(String transportStr) {
        return switch (transportStr.toUpperCase()) {
            case "STDIO" -> TransportType.STDIO;
            case "STREAMABLE_HTTP", "HTTP" -> TransportType.STREAMABLE_HTTP;
            case "SSE", "SSE_LEGACY" -> TransportType.SSE_LEGACY;
            default -> throw new IllegalArgumentException("不支持的传输类型: " + transportStr);
        };
    }

    private Duration parseDuration(Object value, Duration defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) {
            return Duration.ofSeconds(((Number) value).longValue());
        }
        if (value instanceof String str) {
            try {
                return Duration.parse(str);
            } catch (Exception e) {
                return Duration.ofSeconds(Long.parseLong(str));
            }
        }
        return defaultValue;
    }

    private boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
