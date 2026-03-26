package com.lifepilot.meta.infra.shell;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台进程管理工具提供者 — 构建 4 个 process.* 工具。
 *
 * <p>工具列表：
 * <ul>
 *   <li>{@code builtin.process.list} — 列出后台进程（LOW）</li>
 *   <li>{@code builtin.process.output} — 读取进程输出（LOW）</li>
 *   <li>{@code builtin.process.write} — 写入进程输入（MEDIUM）</li>
 *   <li>{@code builtin.process.kill} — 终止后台进程（HIGH）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class ProcessToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ProcessToolProvider.class);
    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final BackgroundProcessManager processManager;

    public ProcessToolProvider(BackgroundProcessManager processManager) {
        this.processManager = processManager;
    }

    /**
     * 构建 4 个后台进程管理工具。
     *
     * @return 工具列表
     */
    public List<BuiltinTool> buildProcessTools() {
        return List.of(
                buildListTool(),
                buildOutputTool(),
                buildWriteTool(),
                buildKillTool()
        );
    }

    /** 列出后台进程。 */
    private BuiltinTool buildListTool() {
        return BuiltinTool.builder()
                .id("builtin.process.list")
                .category(ToolCategory.PERCEPTION)
                .name("列出后台进程")
                .description("列出所有后台进程的 sessionId、命令、状态和启动时间")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .tags(INFRA_TAGS)
                .executor(this::executeList)
                .build();
    }

    /** 读取进程输出。 */
    private BuiltinTool buildOutputTool() {
        return BuiltinTool.builder()
                .id("builtin.process.output")
                .category(ToolCategory.PERCEPTION)
                .name("读取进程输出")
                .description("读取指定后台进程的输出缓冲区增量（自上次读取以来的新内容）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sessionId"),
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "后台进程的 sessionId")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeOutput)
                .build();
    }

    /** 写入进程输入。 */
    private BuiltinTool buildWriteTool() {
        return BuiltinTool.builder()
                .id("builtin.process.write")
                .category(ToolCategory.ACTION)
                .name("写入进程输入")
                .description("向指定后台进程的 stdin 写入内容（可能影响进程行为）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sessionId", "input"),
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "后台进程的 sessionId"),
                                "input", Map.of("type", "string",
                                        "description", "要写入 stdin 的内容")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeWrite)
                .build();
    }

    /** 终止后台进程。 */
    private BuiltinTool buildKillTool() {
        return BuiltinTool.builder()
                .id("builtin.process.kill")
                .category(ToolCategory.ACTION)
                .name("终止后台进程")
                .description("强制终止指定后台进程")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sessionId"),
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "后台进程的 sessionId")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeKill)
                .build();
    }

    // ─────────────────────────────────────────────
    //  工具执行方法
    // ─────────────────────────────────────────────

    private ToolResult executeList(ToolInput input) {
        var processes = processManager.listProcesses();
        var entries = processes.stream()
                .map(p -> {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("sessionId", p.sessionId());
                    entry.put("command", p.command());
                    entry.put("state", p.state().name());
                    entry.put("startTime", p.startTime().toString());
                    entry.put("workDir", p.workDir());
                    return (Map<String, Object>) Map.copyOf(entry);
                })
                .toList();
        return ToolResult.success(Map.of("processes", entries, "count", entries.size()));
    }

    private ToolResult executeOutput(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            String output = processManager.readOutput(sessionId);
            return ToolResult.success(Map.of("sessionId", sessionId, "output", output));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private ToolResult executeWrite(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            String data = input.getParam("input", String.class);
            processManager.writeInput(sessionId, data);
            return ToolResult.success(Map.of("sessionId", sessionId, "written", data.length()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("写入后台进程失败: error={}", e.getMessage(), e);
            return ToolResult.error("写入失败: " + e.getMessage());
        }
    }

    private ToolResult executeKill(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            processManager.killProcess(sessionId);
            return ToolResult.success(Map.of("sessionId", sessionId, "message", "进程已终止"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
