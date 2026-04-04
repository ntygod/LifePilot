package com.lifepilot.meta.infra.shell;

import com.lifepilot.meta.infra.shell.session.TmuxSessionManager;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shell 工具提供者。
 *
 * <p>按领域边界拆分为两个工具：
 * <ul>
 *   <li>{@code shell.exec} — 命令执行（始终注册）</li>
 *   <li>{@code shell.process} — 后台进程和持久会话管理（仅当 processManager 或 sessionManager 可用时注册）</li>
 * </ul>
 * 参考 OpenClaw 的 exec / process 拆分设计。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class ShellToolProvider {

    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final ShellExecToolExecutor shellExecExecutor;
    @Nullable
    private final BackgroundProcessManager processManager;
    @Nullable
    private final TmuxSessionManager sessionManager;

    public ShellToolProvider(ShellExecToolExecutor shellExecExecutor,
                             @Nullable BackgroundProcessManager processManager,
                             @Nullable TmuxSessionManager sessionManager) {
        this.shellExecExecutor = shellExecExecutor;
        this.processManager = processManager;
        this.sessionManager = sessionManager;
    }

    /**
     * 构建 Shell 相关工具列表。
     *
     * @return 工具列表（1-2 个）
     */
    public List<BuiltinTool> buildShellTools() {
        var tools = new ArrayList<BuiltinTool>();
        tools.add(buildExecTool());
        if (processManager != null || sessionManager != null) {
            tools.add(buildProcessTool());
        }
        return List.copyOf(tools);
    }

    // ─────────────────────────────────────────────
    //  shell.exec — 命令执行
    // ─────────────────────────────────────────────

    private BuiltinTool buildExecTool() {
        return BuiltinTool.builder()
                .id("shell.exec")
                .category(ToolCategory.ACTION)
                .name("执行命令")
                .description("在操作系统中执行 Shell 命令。" +
                        "支持 background=true 立即后台执行（返回 sessionId），" +
                        "yieldMs=N 先同步等 N 毫秒、超时自动转后台（适合不确定时长的命令）。" +
                        "env 参数可注入环境变量（如 API Key），shell 参数可指定 Unix 解释器（bash/zsh，默认 sh），" +
                        "pty=true 分配伪终端（交互式 TUI 程序需要，仅 Unix）。" +
                        "简单代码执行请用 code.execute，Git 操作请用 git.query/git.mutate。" +
                        "管理后台进程或持久会话请用 shell.process。")
                .inputSchema(JsonSchema.of(buildExecSchema()))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.workspacePaths("workingDirectory", "cwd")
                ))
                .tags(INFRA_TAGS)
                .executor(shellExecExecutor::execute)
                .build();
    }

    private Map<String, Object> buildExecSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("command", Map.of("type", "string", "description", "Shell 命令"));
        properties.put("workingDirectory", Map.of("type", "string", "description", "工作目录路径"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "description", "命令超时时间（秒）"));
        properties.put("background", Map.of("type", "boolean", "description", "是否立即后台执行"));
        properties.put("yieldMs", Map.of("type", "integer", "description", "同步等待毫秒数，超时后自动转后台"));
        properties.put("pty", Map.of("type", "boolean", "description", "是否分配伪终端（PTY）"));
        properties.put("shell", Map.of("type", "string", "description",
                "Shell 解释器（bash/zsh/sh 等），仅 Unix 生效，默认 sh。Windows 固定使用 PowerShell"));
        properties.put("env", Map.of("type", "object", "description",
                "额外环境变量键值对，注入到子进程环境中"));

        return Map.of(
                "type", "object",
                "required", List.of("command"),
                "properties", Map.copyOf(properties)
        );
    }

    // ─────────────────────────────────────────────
    //  shell.process — 后台进程与持久会话管理
    // ─────────────────────────────────────────────

    private BuiltinTool buildProcessTool() {
        var executor = new ShellProcessDispatchExecutor(processManager, sessionManager);
        return BuiltinTool.builder()
                .id("shell.process")
                .category(ToolCategory.ACTION)
                .name("进程与会话管理")
                .description(buildProcessDescription())
                .inputSchema(JsonSchema.of(buildProcessSchema()))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    private String buildProcessDescription() {
        var sb = new StringBuilder("管理通过 shell.exec(background=true/yieldMs) 启动的后台进程，或管理持久终端会话。");
        sb.append("通过 action 参数支持：");
        if (processManager != null) {
            sb.append("【后台进程】list=列出所有后台进程及状态，output=读取增量输出（自上次读取以来的新内容），");
            sb.append("write=向进程 stdin 写入内容（如回答交互提示），kill=强制终止进程");
        }
        if (sessionManager != null) {
            if (processManager != null) sb.append("；");
            sb.append("【持久会话（tmux）】适用于需要跨多次调用保持环境状态的场景。");
            sb.append("session-create=创建会话，session-exec=在会话中执行命令并等待完成，");
            sb.append("session-read=读取屏幕内容，session-write=发送原始输入（不附加回车），");
            sb.append("session-signal=发送信号（如 SIGINT 中断），");
            sb.append("session-list=列出会话，session-close=关闭会话，session-resize=调整窗口大小");
        }
        sb.append("。要执行新命令请用 shell.exec。");
        return sb.toString();
    }

    private Map<String, Object> buildProcessSchema() {
        var actionEnum = new ArrayList<String>();
        if (processManager != null) {
            actionEnum.addAll(List.of("list", "output", "write", "kill"));
        }
        if (sessionManager != null) {
            actionEnum.addAll(List.of("session-create", "session-exec", "session-write",
                    "session-read", "session-signal", "session-list", "session-close", "session-resize"));
        }

        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.copyOf(actionEnum),
                "description", "操作类型"
        ));
        properties.put("sessionId", Map.of("type", "string", "description", "进程或持久会话的 sessionId"));
        if (processManager != null) {
            properties.put("input", Map.of("type", "string", "description", "action=write/session-write 时写入的输入内容"));
        }
        if (sessionManager != null) {
            properties.put("command", Map.of("type", "string", "description", "action=session-exec 时的 Shell 命令"));
            if (processManager == null) {
                properties.put("input", Map.of("type", "string", "description", "action=session-write 时写入的输入内容"));
            }
            properties.put("name", Map.of("type", "string", "description", "action=session-create 时的会话名称"));
            properties.put("workDir", Map.of("type", "string", "description", "action=session-create 时的初始工作目录"));
            properties.put("signal", Map.of("type", "string", "description", "action=session-signal 时的信号名称，如 SIGINT、SIGTERM"));
            properties.put("cols", Map.of("type", "integer", "description", "action=session-resize 时的终端列数"));
            properties.put("rows", Map.of("type", "integer", "description", "action=session-resize 时的终端行数"));
        }

        return Map.of(
                "type", "object",
                "required", List.of("action"),
                "properties", Map.copyOf(properties)
        );
    }
}
