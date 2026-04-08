package com.lifepilot.meta.infra.shell;

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
            return ToolResult.success(Map.copyOf(data));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
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
