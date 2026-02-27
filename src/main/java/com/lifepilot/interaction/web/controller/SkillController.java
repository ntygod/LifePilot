package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

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

    public SkillController(SkillRegistry skillRegistry,
                           McpServerRegistry mcpServerRegistry,
                           DynamicToolRegistry toolRegistry) {
        this.skillRegistry = skillRegistry;
        this.mcpServerRegistry = mcpServerRegistry;
        this.toolRegistry = toolRegistry;
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
        log.info("Skill 已注销: id={}", id);
        return ResponseEntity.noContent().build();
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
}
