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
 * <p>集中管理统一的 {@code shell} 元能力工具，通过 action 参数路由到
 * exec / process / session 三类 shell 相关能力。
 * action enum 和 description 根据当前环境可用组件动态构建。</p>
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

    public List<BuiltinTool> buildShellTools() {
        var executor = new ShellActionDispatchExecutor(shellExecExecutor, processManager, sessionManager);
        return List.of(buildShellTool(executor));
    }

    private BuiltinTool buildShellTool(ShellActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("shell")
                .category(ToolCategory.ACTION)
                .name("Shell 与进程管理")
                .description(buildDescription())
                .inputSchema(JsonSchema.of(buildSchema()))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.workspacePaths("workingDirectory", "cwd")
                ))
                .tags(INFRA_TAGS)
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    private String buildDescription() {
        var sb = new StringBuilder();
        sb.append("在操作系统中执行命令");
        if (processManager != null) sb.append("、管理后台进程");
        if (sessionManager != null) sb.append("和持久终端会话");
        sb.append("。通过 action 参数支持：");
        sb.append("【命令执行】exec=执行 Shell 命令（支持 background/yieldMs/pty）");
        if (processManager != null) {
            sb.append("；【后台进程】process-list=列出后台进程，process-output=读取输出，");
            sb.append("process-write=写入 stdin，process-kill=终止进程");
        }
        if (sessionManager != null) {
            sb.append("；【持久会话】session-create=创建 tmux 会话，session-exec=在会话中执行命令，");
            sb.append("session-read=读取屏幕，session-write=发送输入，session-signal=发送信号，");
            sb.append("session-list=列出会话，session-close=关闭会话，session-resize=调整窗口");
        }
        sb.append("。简单代码执行请用 code.execute，Git 操作请用 git.query/git.mutate。");
        return sb.toString();
    }

    private Map<String, Object> buildSchema() {
        var actionEnum = new ArrayList<String>();
        actionEnum.add("exec");
        if (processManager != null) {
            actionEnum.addAll(List.of("process-list", "process-output", "process-write", "process-kill"));
        }
        if (sessionManager != null) {
            actionEnum.addAll(List.of("session-create", "session-exec", "session-write",
                    "session-read", "session-signal", "session-list", "session-close", "session-resize"));
        }

        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.copyOf(actionEnum),
                "description", "Shell 操作类型"
        ));
        properties.put("command", Map.of("type", "string", "description", "action=exec/session-exec 时的 Shell 命令"));
        properties.put("workingDirectory", Map.of("type", "string", "description", "action=exec 时的工作目录路径"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "description", "action=exec 时命令超时时间（秒）"));
        properties.put("background", Map.of("type", "boolean", "description", "action=exec 时是否立即后台执行"));
        properties.put("yieldMs", Map.of("type", "integer", "description", "action=exec 时同步等待毫秒数，超时后自动转后台"));
        properties.put("pty", Map.of("type", "boolean", "description", "action=exec 时是否分配伪终端（PTY）"));
        properties.put("sessionId", Map.of("type", "string", "description", "进程或持久会话的 sessionId"));
        properties.put("input", Map.of("type", "string", "description", "写入后台进程或持久会话的输入内容"));
        if (sessionManager != null) {
            properties.put("name", Map.of("type", "string", "description", "action=session-create 时的会话名称"));
            properties.put("workDir", Map.of("type", "string", "description", "action=session-create 时的初始工作目录"));
            properties.put("signal", Map.of("type", "string", "description", "action=session-signal 时的信号名称，如 SIGINT、SIGTERM"));
            properties.put("cols", Map.of("type", "integer", "description", "action=session-resize 时的终端列数"));
            properties.put("rows", Map.of("type", "integer", "description", "action=session-resize 时的终端行数"));
        }

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", List.of("action"));
        schema.put("properties", properties);
        return schema;
    }
}
