package com.lifepilot.meta.infra.shell.session;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shell 持久会话工具提供者 — 构建 8 个 shell.session.* 工具。
 *
 * <p>工具列表：
 * <ul>
 *   <li>{@code shell.session.create} — 创建持久会话（HIGH）</li>
 *   <li>{@code shell.session.exec} — 在会话中执行命令（HIGH）</li>
 *   <li>{@code shell.session.write} — 向会话写入原始输入（MEDIUM）</li>
 *   <li>{@code shell.session.read} — 读取会话输出（LOW）</li>
 *   <li>{@code shell.session.signal} — 向会话发送信号（HIGH）</li>
 *   <li>{@code shell.session.list} — 列出持久会话（LOW）</li>
 *   <li>{@code shell.session.close} — 关闭持久会话（MEDIUM）</li>
 *   <li>{@code shell.session.resize} — 调整会话窗口大小（LOW）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class SessionToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SessionToolProvider.class);
    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final TmuxSessionManager sessionManager;

    public SessionToolProvider(TmuxSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 构建所有 Shell 持久会话工具的 BuiltinTool 列表。
     *
     * @return 8 个持久会话工具列表
     */
    public List<BuiltinTool> buildSessionTools() {
        var tools = new ArrayList<BuiltinTool>();

        tools.add(buildCreateTool());
        tools.add(buildExecTool());
        tools.add(buildWriteTool());
        tools.add(buildReadTool());
        tools.add(buildSignalTool());
        tools.add(buildListTool());
        tools.add(buildCloseTool());
        tools.add(buildResizeTool());

        return List.copyOf(tools);
    }

    /** 构建创建持久会话工具。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("shell.session.create")
                .category(ToolCategory.ACTION)
                .name("创建持久会话")
                .description("创建一个基于 tmux 的持久 Shell 会话。会话在多次工具调用间保持状态（环境变量、工作目录、进程等），适用于需要交互式操作的场景")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string",
                                        "description", "会话名称（可选），用于标识用途"),
                                "workDir", Map.of("type", "string",
                                        "description", "初始工作目录，默认用户主目录")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeCreate)
                .build();
    }

    /** 构建在会话中执行命令工具。 */
    private BuiltinTool buildExecTool() {
        return BuiltinTool.builder()
                .id("shell.session.exec")
                .category(ToolCategory.ACTION)
                .name("在持久会话中执行命令")
                .description("在指定的持久会话中执行命令并等待完成，返回命令输出。会话保持之前的状态（环境变量、工作目录等）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sessionId", "command"),
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "持久会话的 sessionId"),
                                "command", Map.of("type", "string",
                                        "description", "要执行的 Shell 命令")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeExec)
                .build();
    }

    /** 构建写入会话输入工具。 */
    private BuiltinTool buildWriteTool() {
        return BuiltinTool.builder()
                .id("shell.session.write")
                .category(ToolCategory.ACTION)
                .name("向持久会话写入输入")
                .description("向持久会话发送原始输入（不自动附加回车），用于交互式程序的输入响应")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sessionId", "input"),
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "持久会话的 sessionId"),
                                "input", Map.of("type", "string",
                                        "description", "要写入的原始内容")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeWrite)
                .build();
    }

    /** 构建读取会话输出工具。 */
    private BuiltinTool buildReadTool() {
        return BuiltinTool.builder()
                .id("shell.session.read")
                .category(ToolCategory.PERCEPTION)
                .name("读取持久会话输出")
                .description("读取持久会话当前的屏幕输出内容，用于检查长时间运行命令的进度或交互式程序的输出")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sessionId"),
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "持久会话的 sessionId")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeRead)
                .build();
    }

    /** 构建发送信号工具。 */
    private BuiltinTool buildSignalTool() {
        return BuiltinTool.builder()
                .id("shell.session.signal")
                .category(ToolCategory.ACTION)
                .name("向持久会话发送信号")
                .description("向持久会话中运行的进程发送信号（如 SIGINT 中断、SIGTERM 终止）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sessionId", "signal"),
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "持久会话的 sessionId"),
                                "signal", Map.of("type", "string",
                                        "description", "信号名称，如 SIGINT、SIGTERM、SIGHUP")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeSignal)
                .build();
    }

    /** 构建列出持久会话工具。 */
    private BuiltinTool buildListTool() {
        return BuiltinTool.builder()
                .id("shell.session.list")
                .category(ToolCategory.PERCEPTION)
                .name("列出持久会话")
                .description("列出所有活跃的持久会话及其状态信息")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .tags(INFRA_TAGS)
                .executor(this::executeList)
                .build();
    }

    /** 构建关闭持久会话工具。 */
    private BuiltinTool buildCloseTool() {
        return BuiltinTool.builder()
                .id("shell.session.close")
                .category(ToolCategory.ACTION)
                .name("关闭持久会话")
                .description("关闭并销毁指定的持久会话，释放 tmux 资源")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sessionId"),
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "持久会话的 sessionId")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeClose)
                .build();
    }

    /** 构建调整会话窗口大小工具。 */
    private BuiltinTool buildResizeTool() {
        return BuiltinTool.builder()
                .id("shell.session.resize")
                .category(ToolCategory.ACTION)
                .name("调整持久会话窗口大小")
                .description("调整持久会话的终端窗口大小（列数和行数）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("sessionId", "cols", "rows"),
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "持久会话的 sessionId"),
                                "cols", Map.of("type", "integer",
                                        "description", "终端列数"),
                                "rows", Map.of("type", "integer",
                                        "description", "终端行数")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeResize)
                .build();
    }

    // ─────────────────────────────────────────────
    //  工具执行方法
    // ─────────────────────────────────────────────

    private ToolResult executeCreate(ToolInput input) {
        try {
            String name = input.getOptionalParam("name", String.class).orElse(null);
            String workDir = input.getOptionalParam("workDir", String.class).orElse(null);
            String sessionId = sessionManager.createSession(name, workDir);
            return ToolResult.success(Map.of(
                    "sessionId", sessionId,
                    "message", "持久会话已创建，使用 shell.session.exec 执行命令"
            ));
        } catch (IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("创建持久会话失败: error={}", e.getMessage(), e);
            return ToolResult.error("创建持久会话失败: " + e.getMessage());
        }
    }

    private ToolResult executeExec(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            String command = input.getParam("command", String.class);
            String output = sessionManager.execInSession(sessionId, command);
            var data = new LinkedHashMap<String, Object>();
            data.put("sessionId", sessionId);
            data.put("output", output);
            return ToolResult.success(Map.copyOf(data));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("在持久会话中执行命令失败: error={}", e.getMessage(), e);
            return ToolResult.error("命令执行失败: " + e.getMessage());
        }
    }

    private ToolResult executeWrite(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            String data = input.getParam("input", String.class);
            sessionManager.writeToSession(sessionId, data);
            return ToolResult.success(Map.of("sessionId", sessionId, "written", data.length()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("向持久会话写入失败: error={}", e.getMessage(), e);
            return ToolResult.error("写入失败: " + e.getMessage());
        }
    }

    private ToolResult executeRead(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            String output = sessionManager.readSession(sessionId);
            var data = new LinkedHashMap<String, Object>();
            data.put("sessionId", sessionId);
            data.put("output", output);
            return ToolResult.success(Map.copyOf(data));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("读取持久会话输出失败: error={}", e.getMessage(), e);
            return ToolResult.error("读取失败: " + e.getMessage());
        }
    }

    private ToolResult executeSignal(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            String signal = input.getParam("signal", String.class);
            sessionManager.signalSession(sessionId, signal);
            return ToolResult.success(Map.of("sessionId", sessionId, "signal", signal, "message", "信号已发送"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("向持久会话发送信号失败: error={}", e.getMessage(), e);
            return ToolResult.error("信号发送失败: " + e.getMessage());
        }
    }

    private ToolResult executeList(ToolInput input) {
        var sessionList = sessionManager.listSessions();
        var entries = sessionList.stream()
                .map(s -> {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("sessionId", s.sessionId());
                    entry.put("name", s.name());
                    entry.put("state", s.state().name());
                    entry.put("cwd", s.cwd());
                    entry.put("createdAt", s.createdAt().toString());
                    entry.put("lastAccessTime", s.lastAccessTime().toString());
                    return (Map<String, Object>) Map.copyOf(entry);
                })
                .toList();
        return ToolResult.success(Map.of("sessions", entries, "count", entries.size()));
    }

    private ToolResult executeClose(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            sessionManager.closeSession(sessionId);
            return ToolResult.success(Map.of("sessionId", sessionId, "message", "持久会话已关闭"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private ToolResult executeResize(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            int cols = input.getParam("cols", Number.class).intValue();
            int rows = input.getParam("rows", Number.class).intValue();
            sessionManager.resizeSession(sessionId, cols, rows);
            return ToolResult.success(Map.of(
                    "sessionId", sessionId,
                    "cols", cols,
                    "rows", rows,
                    "message", "窗口大小已调整"
            ));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("调整持久会话窗口大小失败: error={}", e.getMessage(), e);
            return ToolResult.error("窗口大小调整失败: " + e.getMessage());
        }
    }
}
