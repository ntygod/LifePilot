package com.lifepilot.meta.infra.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.meta.infra.shell.session.TmuxSessionManager;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 后台进程与持久会话 action 路由执行器。
 *
 * <p>承接 shell.process 工具的所有 action：
 * 后台进程管理（list/output/write/kill）和持久会话管理（session-*）。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
public class ShellProcessDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShellProcessDispatchExecutor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 识别为"任务最终结果"的 JSON Lines 事件类型（Claude Code / Codex / Gemini 通用）。 */
    private static final Set<String> TERMINAL_EVENT_TYPES = Set.of(
            "result",          // Claude Code
            "task_complete",   // Codex
            "session_ended"    // Codex
    );

    @Nullable
    private final BackgroundProcessManager processManager;
    @Nullable
    private final TmuxSessionManager sessionManager;

    public ShellProcessDispatchExecutor(@Nullable BackgroundProcessManager processManager,
                                         @Nullable TmuxSessionManager sessionManager) {
        this.processManager = processManager;
        this.sessionManager = sessionManager;

        if (processManager != null) {
            register("list",
                    RiskLevel.LOW,
                    ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE),
                    this::executeProcessList);
            register("output",
                    RiskLevel.LOW,
                    ToolExecutionSemantics.of(
                            PermissionActionType.GENERIC_TOOL_OPERATION,
                            ToolSchedulingMode.PARALLEL_SAFE,
                            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                    ),
                    this::executeProcessOutput);
            register("write",
                    RiskLevel.MEDIUM,
                    ToolExecutionSemantics.of(
                            PermissionActionType.GENERIC_TOOL_OPERATION,
                            ToolSchedulingMode.SEQUENTIAL,
                            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                    ),
                    this::executeProcessWrite);
            register("kill",
                    RiskLevel.HIGH,
                    ToolExecutionSemantics.of(
                            PermissionActionType.GENERIC_TOOL_OPERATION,
                            ToolSchedulingMode.SEQUENTIAL,
                            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                    ),
                    this::executeProcessKill);
        }

        if (sessionManager != null) {
            register("session-create",
                    RiskLevel.HIGH,
                    ToolExecutionSemantics.of(
                            PermissionActionType.EXECUTE_SHELL,
                            ToolSchedulingMode.SEQUENTIAL,
                            ToolScopeResolvers.none()
                    ),
                    this::executeSessionCreate);
            register("session-exec",
                    RiskLevel.HIGH,
                    ToolExecutionSemantics.of(
                            PermissionActionType.EXECUTE_SHELL,
                            ToolSchedulingMode.SEQUENTIAL,
                            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                    ),
                    this::executeSessionExec);
            register("session-write",
                    RiskLevel.MEDIUM,
                    ToolExecutionSemantics.of(
                            PermissionActionType.GENERIC_TOOL_OPERATION,
                            ToolSchedulingMode.SEQUENTIAL,
                            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                    ),
                    this::executeSessionWrite);
            register("session-read",
                    RiskLevel.LOW,
                    ToolExecutionSemantics.of(
                            PermissionActionType.GENERIC_TOOL_OPERATION,
                            ToolSchedulingMode.PARALLEL_SAFE,
                            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                    ),
                    this::executeSessionRead);
            register("session-signal",
                    RiskLevel.HIGH,
                    ToolExecutionSemantics.of(
                            PermissionActionType.EXECUTE_SHELL,
                            ToolSchedulingMode.SEQUENTIAL,
                            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                    ),
                    this::executeSessionSignal);
            register("session-list",
                    RiskLevel.LOW,
                    ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE),
                    this::executeSessionList);
            register("session-close",
                    RiskLevel.MEDIUM,
                    ToolExecutionSemantics.of(
                            PermissionActionType.GENERIC_TOOL_OPERATION,
                            ToolSchedulingMode.SEQUENTIAL,
                            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                    ),
                    this::executeSessionClose);
            register("session-resize",
                    RiskLevel.LOW,
                    ToolExecutionSemantics.of(
                            PermissionActionType.GENERIC_TOOL_OPERATION,
                            ToolSchedulingMode.SEQUENTIAL,
                            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                    ),
                    this::executeSessionResize);
        }
    }

    // ─────────────────────────────────────────────
    //  后台进程管理
    // ─────────────────────────────────────────────

    private ToolResult executeProcessList(ToolInput input) {
        var processes = processManager.listProcesses();
        var entries = processes.stream().map(p -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("sessionId", p.sessionId());
            entry.put("command", p.command());
            entry.put("state", p.state().name());
            if (p.exitCode() != null) entry.put("exitCode", p.exitCode());
            entry.put("startTime", p.startTime().toString());
            entry.put("workDir", p.workDir());
            return (Map<String, Object>) Map.copyOf(entry);
        }).toList();
        return ToolResult.success(Map.of("processes", entries, "count", entries.size()));
    }

    private ToolResult executeProcessOutput(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            ProcessOutputChunk chunk = processManager.readOutputChunk(sessionId);
            var data = new LinkedHashMap<String, Object>();
            data.put("sessionId", sessionId);
            data.put("output", chunk.output());
            data.put("stdout", chunk.stdout());
            data.put("stderr", chunk.stderr());
            data.put("state", chunk.state().name());
            if (chunk.exitCode() != null) data.put("exitCode", chunk.exitCode());

            // 自动解析 stream-json 的最后一个"终态事件"（result / task_complete / session_ended）
            // 让 LLM 不用扫整个日志就能拿到最终摘要
            Map<String, Object> lastResult = extractLastTerminalEvent(chunk.stdout());
            if (lastResult != null) {
                data.put("lastResult", lastResult);
            }

            return ToolResult.success(Map.copyOf(data));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * 从 stdout 的 JSON Lines 增量中扫描最后一个"终态事件"。
     *
     * <p>支持的事件类型在 {@link #TERMINAL_EVENT_TYPES}。对每个匹配事件，
     * 只抽取常见摘要字段（subtype/result/is_error/duration_ms/total_cost_usd），
     * 避免把完整 stream-json 内嵌到响应里。</p>
     *
     * @param stdout 当次 output 增量
     * @return 最后一个终态事件的摘要；未检测到返回 null
     */
    @Nullable
    private Map<String, Object> extractLastTerminalEvent(@Nullable String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        Map<String, Object> last = null;
        for (String line : stdout.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(trimmed);
                String type = node.path("type").asText(null);
                if (type == null || !TERMINAL_EVENT_TYPES.contains(type)) {
                    continue;
                }
                var summary = new LinkedHashMap<String, Object>();
                summary.put("type", type);
                if (node.hasNonNull("subtype")) summary.put("subtype", node.get("subtype").asText());
                if (node.hasNonNull("result")) summary.put("result", node.get("result").asText());
                if (node.hasNonNull("is_error")) summary.put("is_error", node.get("is_error").asBoolean());
                if (node.hasNonNull("duration_ms")) summary.put("duration_ms", node.get("duration_ms").asLong());
                if (node.hasNonNull("total_cost_usd")) summary.put("total_cost_usd", node.get("total_cost_usd").asDouble());
                last = Map.copyOf(summary);
            } catch (Exception ignore) {
                // 非 JSON 行或解析失败，静默忽略
            }
        }
        return last;
    }

    private ToolResult executeProcessWrite(ToolInput input) {
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

    private ToolResult executeProcessKill(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            processManager.killProcess(sessionId);
            return ToolResult.success(Map.of("sessionId", sessionId, "message", "进程已终止"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  持久会话管理
    // ─────────────────────────────────────────────

    private ToolResult executeSessionCreate(ToolInput input) {
        try {
            String name = input.getOptionalParam("name", String.class).orElse(null);
            String workDir = input.getOptionalParam("workDir", String.class).orElse(null);
            String sessionId = sessionManager.createSession(name, workDir);
            return ToolResult.success(Map.of("sessionId", sessionId, "message", "持久会话已创建，使用 shell.process(action=session-exec) 执行命令"));
        } catch (IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("创建持久会话失败: error={}", e.getMessage(), e);
            return ToolResult.error("创建持久会话失败: " + e.getMessage());
        }
    }

    private ToolResult executeSessionExec(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            String command = input.getParam("command", String.class);
            String output = sessionManager.execInSession(sessionId, command);
            return ToolResult.success(Map.of("sessionId", sessionId, "output", output));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("在持久会话中执行命令失败: error={}", e.getMessage(), e);
            return ToolResult.error("命令执行失败: " + e.getMessage());
        }
    }

    private ToolResult executeSessionWrite(ToolInput input) {
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

    private ToolResult executeSessionRead(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            String output = sessionManager.readSession(sessionId);
            return ToolResult.success(Map.of("sessionId", sessionId, "output", output));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("读取持久会话输出失败: error={}", e.getMessage(), e);
            return ToolResult.error("读取失败: " + e.getMessage());
        }
    }

    private ToolResult executeSessionSignal(ToolInput input) {
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

    private ToolResult executeSessionList(ToolInput input) {
        var sessionList = sessionManager.listSessions();
        var entries = sessionList.stream().map(s -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("sessionId", s.sessionId());
            entry.put("name", s.name());
            entry.put("state", s.state().name());
            entry.put("cwd", s.cwd());
            entry.put("createdAt", s.createdAt().toString());
            entry.put("lastAccessTime", s.lastAccessTime().toString());
            return (Map<String, Object>) Map.copyOf(entry);
        }).toList();
        return ToolResult.success(Map.of("sessions", entries, "count", entries.size()));
    }

    private ToolResult executeSessionClose(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            sessionManager.closeSession(sessionId);
            return ToolResult.success(Map.of("sessionId", sessionId, "message", "持久会话已关闭"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private ToolResult executeSessionResize(ToolInput input) {
        try {
            String sessionId = input.getParam("sessionId", String.class);
            int cols = input.getParam("cols", Number.class).intValue();
            int rows = input.getParam("rows", Number.class).intValue();
            sessionManager.resizeSession(sessionId, cols, rows);
            return ToolResult.success(Map.of("sessionId", sessionId, "cols", cols, "rows", rows, "message", "窗口大小已调整"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            log.error("调整持久会话窗口大小失败: error={}", e.getMessage(), e);
            return ToolResult.error("窗口大小调整失败: " + e.getMessage());
        }
    }
}
