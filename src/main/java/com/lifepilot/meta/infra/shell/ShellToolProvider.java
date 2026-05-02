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
        boolean windows = com.lifepilot.meta.infra.shell.ShellProcessFactory.isWindows();
        String shellHint = windows
                ? "**当前运行环境：Windows + PowerShell**（不是 bash）。命令必须用 PowerShell 语法：路径用反斜杠或正斜杠均可但禁用 `/home/xxx` 等 Linux 路径；变量用 `$env:NAME` 而非 `$NAME`；多行脚本不要用 bash heredoc（`cat <<EOF`）会解析失败；需要 bash 特性时显式 `bash -c \"...\"`。"
                : "**当前运行环境：Unix + sh**（默认 sh，可通过 shell 参数指定 bash/zsh）。";
        return BuiltinTool.builder()
                .id("shell.exec")
                .category(ToolCategory.ACTION)
                .name("执行命令")
                .description("""
                        执行 shell 命令。短命令默认同步；长服务用 background=true；不确定耗时用 yieldMs。后台进程用 shell.process 读取/终止。

                        %s

                        安全护栏：与 code 同款规则，不可绕过 —— 永久阻断（rm -rf 系统目录 / mkfs / dd / shutdown / fork bomb 等）；\
                        默认拒绝（rm -rf 子目录 / chmod -R 777 / git reset --hard / curl|sh / sudo 等）。\
                        用户要求执行此类命令时直接告知会被阻断，不要改写为"等效平台命令"绕过；删除走 file.manage(action=delete) 由用户明确路径。""".formatted(shellHint))
                .inputSchema(JsonSchema.of(buildExecSchema()))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.workspacePaths("workingDirectory", "cwd")
                ))
                .tags(List.of("命令", "执行", "脚本", "终端", "shell", "exec", "command", "bash"))
                .executor(shellExecExecutor::execute)
                .build();
    }

    private Map<String, Object> buildExecSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("command", Map.of("type", "string",
                "description", "Shell 命令原文。需要 bash 特性时显式写 `bash -c \"...\"`（后台模式不支持 shell 参数）。Windows 平台下 stderr 可能以 PowerShell CLIXML 格式返回（以 `#< CLIXML` 开头的 XML），这是 stderr 而非正常输出，不应视作 stdout 内容"));
        properties.put("workingDirectory", Map.of("type", "string",
                "description", "工作目录绝对路径。**不传**则使用用户配置的默认工作目录（推荐默认不传）。仅在明确需要其他目录时才显式传值，如 git worktree 路径、已知项目根目录等。不要传 `/` 或 `C:\\` 等盘根路径"));
        properties.put("timeoutSeconds", Map.of("type", "integer",
                "description", "同步模式超时（秒），默认 120。到期强杀进程并收集部分输出。仅同步模式生效"));
        properties.put("background", Map.of("type", "boolean",
                "description", "立即后台执行，返回 sessionId。success 仅代表启动成功，不代表命令成功 —— 必须后续用 shell.process(action=output) 检查真实 exitCode。服务类命令（永不退出）必选此模式"));
        properties.put("yieldMs", Map.of("type", "integer",
                "description", "同步等待毫秒数（上限 120000），超时自动转后台。典型值 2000-10000。快则返同步结果 {exitCode,stdout,stderr}，慢则返 {sessionId, backgrounded:true}。yieldMs=0 等价于 background=true"));
        properties.put("pty", Map.of("type", "boolean",
                "description", "分配伪终端（仅 Unix 同步模式）。Windows 会被忽略并降级"));
        properties.put("shell", Map.of("type", "string",
                "description", "Unix 解释器（bash/zsh 等），仅同步模式生效。background/yieldMs 转后台时固定用 sh。Windows 固定 PowerShell，此参数被忽略"));
        properties.put("env", Map.of("type", "object",
                "description", "额外环境变量键值对，合并到子进程环境",
                "additionalProperties", Map.of("type", "string")));

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
                .tags(List.of("进程", "后台", "会话", "管理", "终止", "tmux", "process", "session", "shell", "kill"))
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    private String buildProcessDescription() {
        return """
                        管理 shell_exec 启动的后台进程与 tmux 会话。
                        action: list(列出所有后台进程及状态) output(读取指定进程的输出，sessionId必填) write(向进程 stdin 写入，sessionId必填) kill(终止进程/会话，sessionId必填) session-list(列出 tmux 会话) session-info(查看会话详情) session-kill(终止 tmux 会话)。
                        output 返回内容可能很大，用 tailLines 限制行数。进程已退出时 status 会标为 exited。""";
    }

    private String buildActionDescription() {
        var parts = new ArrayList<String>();
        parts.add("操作类型。");
        if (processManager != null) {
            parts.add("""
                    后台进程：list(无需 sessionId，返回所有进程摘要)、\
                    output(读 sessionId 增量输出，多次调用每次只返新内容)、\
                    write(向 sessionId 的 stdin 写 input)、\
                    kill(强制终止 sessionId，对已完成/不存在的进程安全幂等)。""");
        }
        if (sessionManager != null) {
            parts.add("""
                    持久会话：session-create(创建，可带 name/workDir)、\
                    session-exec(sessionId 执行 command)、\
                    session-write(向 sessionId 写 input)、\
                    session-read(读 sessionId 当前屏幕)、\
                    session-signal(sessionId 发 signal，支持 SIGINT/SIGTERM 等)、\
                    session-list(列出所有会话)、\
                    session-close(关闭 sessionId)、\
                    session-resize(调整 sessionId 的 cols/rows)。""");
        }
        return String.join(" ", parts);
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
                "description", buildActionDescription()
        ));
        properties.put("sessionId", Map.of("type", "string",
                "description", "进程或持久会话的 sessionId（list/session-list 不需要，其他 action 必填）"));
        if (processManager != null) {
            properties.put("input", Map.of("type", "string",
                    "description", "write/session-write 写入 stdin 的内容（通常需要以 \\n 结尾触发命令）"));
        }
        if (sessionManager != null) {
            properties.put("command", Map.of("type", "string",
                    "description", "session-exec 要在会话内执行的 Shell 命令"));
            if (processManager == null) {
                properties.put("input", Map.of("type", "string",
                        "description", "session-write 写入 stdin 的内容（通常需要以 \\n 结尾触发命令）"));
            }
            properties.put("name", Map.of("type", "string",
                    "description", "session-create 会话名称（可选，默认自动生成）"));
            properties.put("workDir", Map.of("type", "string",
                    "description", "session-create 初始工作目录（可选，默认用户主目录）"));
            properties.put("signal", Map.of("type", "string",
                    "description", "session-signal 信号名，如 SIGINT（Ctrl+C）、SIGTERM（优雅终止）、SIGHUP"));
            properties.put("cols", Map.of("type", "integer", "description", "session-resize 终端列数"));
            properties.put("rows", Map.of("type", "integer", "description", "session-resize 终端行数"));
        }

        var deps = new LinkedHashMap<String, List<String>>();
        if (processManager != null) {
            deps.put("output", List.of("sessionId"));
            deps.put("write", List.of("sessionId", "input"));
            deps.put("kill", List.of("sessionId"));
        }
        if (sessionManager != null) {
            deps.put("session-exec", List.of("sessionId", "command"));
            deps.put("session-write", List.of("sessionId", "input"));
            deps.put("session-read", List.of("sessionId"));
            deps.put("session-signal", List.of("sessionId", "signal"));
            deps.put("session-close", List.of("sessionId"));
            deps.put("session-resize", List.of("sessionId"));
        }

        return Map.of(
                "type", "object",
                "required", List.of("action"),
                "properties", Map.copyOf(properties),
                "dependentRequired", Map.copyOf(deps)
        );
    }
}
