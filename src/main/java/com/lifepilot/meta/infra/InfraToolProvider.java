package com.lifepilot.meta.infra;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
import com.lifepilot.meta.infra.browser.BrowserToolProvider;
import com.lifepilot.meta.infra.code.CodeExecuteToolExecutor;
import com.lifepilot.meta.infra.file.FileToolProvider;
import com.lifepilot.meta.infra.file.history.FileEditHistory;
import com.lifepilot.meta.infra.file.history.LintHookExecutor;
import com.lifepilot.meta.infra.git.GitCommandExecutor;
import com.lifepilot.meta.infra.git.GitToolProvider;
import com.lifepilot.meta.infra.reason.CalculateToolExecutor;
import com.lifepilot.meta.infra.shell.BackgroundProcessManager;
import com.lifepilot.meta.infra.shell.ProcessToolProvider;
import com.lifepilot.meta.infra.shell.ShellExecToolExecutor;
import com.lifepilot.meta.infra.interaction.InteractionBridge;
import com.lifepilot.meta.infra.interaction.InteractionToolProvider;
import com.lifepilot.meta.infra.web.HttpRequestToolExecutor;
import com.lifepilot.meta.infra.web.WebFetchToolExecutor;
import com.lifepilot.meta.infra.web.WebSearchConfigProvider;
import com.lifepilot.meta.infra.web.WebSearchToolExecutor;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.workflow.engine.WorkflowCommandService;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.tool.WorkflowToolProvider;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.meta.infra.task.TaskToolProvider;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 基础工具提供者 — 注册 Infrastructure Tool 到 DynamicToolRegistry。
 *
 * <p>所有基础工具 tags 含 {@code "infrastructure"}，始终对所有调用者可用。
 * 当前注册 Web、推理、Shell、浏览器、代码与文件工具，后续任务将逐步添加其他类别工具。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class InfraToolProvider {

    private static final Logger log = LoggerFactory.getLogger(InfraToolProvider.class);
    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final MetaProperties properties;
    private final WebSearchConfigProvider webSearchConfigProvider;
    @Nullable
    private final SandboxSessionManager sandboxSessionManager;
    @Nullable
    private final CodeValidator codeValidator;
    @Nullable
    private final SandboxRepository sandboxRepository;
    @Nullable
    private final InteractionBridge interactionBridge;
    @Nullable
    private final BrowserSessionManager browserSessionManager;
    @Nullable
    private final NotificationService notificationService;
    @Nullable
    private final WorkflowRegistry workflowRegistry;
    @Nullable
    private final WorkflowCommandService workflowCommandService;
    @Nullable
    private final CronTaskRepository cronTaskRepository;
    @Nullable
    private final CronScheduler cronScheduler;
    @Nullable
    private final AgentConfigProperties agentConfigProperties;
    @Nullable
    private final NotificationProperties notificationProperties;
    @Nullable
    private final BackgroundProcessManager backgroundProcessManager;

    public InfraToolProvider(MetaProperties properties,
                             WebSearchConfigProvider webSearchConfigProvider,
                             @Nullable SandboxSessionManager sandboxSessionManager,
                             @Nullable CodeValidator codeValidator,
                             @Nullable SandboxRepository sandboxRepository,
                             @Nullable InteractionBridge interactionBridge,
                             @Nullable BrowserSessionManager browserSessionManager,
                             @Nullable NotificationService notificationService,
                             @Nullable WorkflowRegistry workflowRegistry,
                             @Nullable WorkflowCommandService workflowCommandService,
                             @Nullable CronTaskRepository cronTaskRepository,
                             @Nullable CronScheduler cronScheduler,
                             @Nullable AgentConfigProperties agentConfigProperties,
                             @Nullable NotificationProperties notificationProperties,
                             @Nullable BackgroundProcessManager backgroundProcessManager) {
        this.properties = properties;
        this.webSearchConfigProvider = webSearchConfigProvider;
        this.sandboxSessionManager = sandboxSessionManager;
        this.codeValidator = codeValidator;
        this.sandboxRepository = sandboxRepository;
        this.interactionBridge = interactionBridge;
        this.browserSessionManager = browserSessionManager;
        this.notificationService = notificationService;
        this.workflowRegistry = workflowRegistry;
        this.workflowCommandService = workflowCommandService;
        this.cronTaskRepository = cronTaskRepository;
        this.cronScheduler = cronScheduler;
        this.agentConfigProperties = agentConfigProperties;
        this.notificationProperties = notificationProperties;
        this.backgroundProcessManager = backgroundProcessManager;
    }

    /**
     * 注册基础工具到 DynamicToolRegistry。
     *
     * @param toolRegistry 动态工具注册中心
     */
    public void registerTools(DynamicToolRegistry toolRegistry) {
        // 信息获取工具
        var webSearchExecutor = new WebSearchToolExecutor(webSearchConfigProvider);
        var webFetchExecutor = new WebFetchToolExecutor(properties);
        int totalTools = registerBuiltinTools(toolRegistry, List.of(
                buildWebSearchTool(webSearchExecutor),
                buildWebFetchTool(webFetchExecutor)
        ));

        // HTTP 请求工具
        var httpRequestExecutor = new HttpRequestToolExecutor();
        totalTools += registerBuiltinTools(toolRegistry, List.of(
                buildHttpRequestTool(httpRequestExecutor)
        ));

        // 推理辅助工具
        var calculateExecutor = new CalculateToolExecutor();
        totalTools += registerBuiltinTools(toolRegistry, List.of(
                buildCalculateTool(calculateExecutor)
        ));

        // Shell 执行工具
        var shellExecExecutor = new ShellExecToolExecutor(properties, backgroundProcessManager);
        totalTools += registerBuiltinTools(toolRegistry, List.of(
                buildShellExecTool(shellExecExecutor)
        ));

        // 浏览器自动化工具（委托给 BrowserToolProvider）
        var browserToolProvider = new BrowserToolProvider(browserSessionManager, properties);
        totalTools += registerBuiltinTools(toolRegistry, browserToolProvider.buildBrowserTools());

        // 代码执行工具
        var codeExecuteExecutor = new CodeExecuteToolExecutor(properties, sandboxSessionManager, codeValidator, sandboxRepository);
        totalTools += registerBuiltinTools(toolRegistry, List.of(
                buildCodeExecuteTool(codeExecuteExecutor)
        ));

        // 文件系统工具（委托给 FileToolProvider）
        var fileEditConfig = properties.getInfra().getFileEdit();
        var editHistory = new FileEditHistory(
                fileEditConfig.getUndoMaxDepth(),
                fileEditConfig.getMaxSnapshotSizeBytes());
        var lintHook = new LintHookExecutor();
        var fileToolProvider = new FileToolProvider(properties, editHistory, lintHook);
        totalTools += registerBuiltinTools(toolRegistry, fileToolProvider.buildFileTools());

        // 交互控制工具（委托给 InteractionToolProvider）
        if (interactionBridge != null && notificationService != null) {
            var interactionToolProvider = new InteractionToolProvider(interactionBridge, notificationService, notificationProperties);
            totalTools += registerBuiltinTools(toolRegistry, interactionToolProvider.buildInteractionTools());
        } else {
            log.warn("InteractionBridge 或 NotificationService 不可用，跳过交互控制工具注册");
        }

        // 工作流管理工具（委托给 WorkflowToolProvider）
        if (workflowRegistry != null && workflowCommandService != null) {
            var workflowToolProvider = new WorkflowToolProvider(workflowRegistry, workflowCommandService);
            var workflowTools = workflowToolProvider.buildWorkflowTools();
            totalTools += registerBuiltinTools(toolRegistry, workflowTools);
            log.info("工作流管理工具注册完成: count={}", workflowTools.size());
        } else {
            log.warn("WorkflowRegistry 或 WorkflowCommandService 不可用，跳过工作流管理工具注册");
        }

        // 自主任务工具（委托给 TaskToolProvider）
        if (cronTaskRepository != null && cronScheduler != null && agentConfigProperties != null) {
            var taskToolProvider = new TaskToolProvider(cronTaskRepository, cronScheduler, agentConfigProperties);
            var cronTools = taskToolProvider.buildCronTools();
            var heartbeatTools = taskToolProvider.buildHeartbeatTools();
            totalTools += registerBuiltinTools(toolRegistry, cronTools);
            totalTools += registerBuiltinTools(toolRegistry, heartbeatTools);
            log.info("自主任务工具注册完成: count={}, categories=[cron, heartbeat]",
                    cronTools.size() + heartbeatTools.size());
        } else {
            log.warn("CronTaskRepository 或 CronScheduler 不可用，跳过自主任务工具注册");
        }

        // 后台进程管理工具（委托给 ProcessToolProvider）
        if (backgroundProcessManager != null) {
            var processToolProvider = new ProcessToolProvider(backgroundProcessManager);
            var processTools = processToolProvider.buildProcessTools();
            totalTools += registerBuiltinTools(toolRegistry, processTools);
            log.info("后台进程管理工具注册完成: count={}", processTools.size());
        } else {
            log.warn("BackgroundProcessManager 不可用，跳过后台进程管理工具注册");
        }

        // Git 工具（委托给 GitToolProvider）
        var gitConfig = properties.getInfra().getGit();
        if (gitConfig.isEnabled()) {
            var gitCmd = new GitCommandExecutor(gitConfig);
            if (gitCmd.isGitAvailable()) {
                var gitToolProvider = new GitToolProvider(gitCmd, gitConfig);
                totalTools += registerBuiltinTools(toolRegistry, gitToolProvider.buildGitTools());
                log.info("Git 工具注册完成: count={}", 7);
            } else {
                log.warn("git 不可用，跳过 Git 工具注册");
            }
        }

        log.info("基础工具注册完成: count={}, categories=[web, reason, shell, browser, code, file, interact, workflow, task, process, git]",
                totalTools);
    }

    private int registerBuiltinTools(DynamicToolRegistry toolRegistry, List<BuiltinTool> tools) {
        tools.forEach(toolRegistry::registerBuiltinTool);
        return tools.size();
    }

    // ─────────────────────────────────────────────
    //  信息获取工具构建
    // ─────────────────────────────────────────────

    /** 构建 Web 搜索工具。 */
    private BuiltinTool buildWebSearchTool(WebSearchToolExecutor executor) {
        return BuiltinTool.builder()
                .id("web.search")
                .category(ToolCategory.PERCEPTION)
                .name("Web 搜索")
                .description("通过搜索引擎搜索互联网上的内容。\n" +
                        "当你的知识无法回答用户提出的问题，或用户请求你进行联网搜索时，调用此工具。请从与用户的对话中提取用户想要搜索的内容作为 query 参数的值。" +
                        "搜索结果包含网站的标题、网站的地址（URL）以及网站简介。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "搜索关键词"),
                                "maxResults", Map.of("type", "integer",
                                        "description", "最大返回结果数，默认使用配置值"),
                                "offset", Map.of("type", "integer",
                                        "description", "分页偏移量，跳过前 offset 条结果，默认 0"),
                                "limit", Map.of("type", "integer",
                                        "description", "分页每页数量，默认等于 maxResults")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.HTTP_REQUEST,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.none()
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建 Web 抓取工具。 */
    private BuiltinTool buildWebFetchTool(WebFetchToolExecutor executor) {
        return BuiltinTool.builder()
                .id("web.fetch")
                .category(ToolCategory.PERCEPTION)
                .name("Web 页面抓取")
                .description("抓取指定 URL 的静态网页内容，解析 HTML 提取正文文本。支持 CSS 选择器定向提取")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("url"),
                        "properties", Map.of(
                                "url", Map.of("type", "string",
                                        "description", "目标网页 URL"),
                                "selector", Map.of("type", "string",
                                        "description", "CSS 选择器，用于提取页面特定区域内容（可选）")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.HTTP_REQUEST,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.origins("url")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }


    /** 构建计算工具 — BigDecimal 精确运算。 */
    private BuiltinTool buildCalculateTool(CalculateToolExecutor executor) {
        return BuiltinTool.builder()
                .id("reason.calculate")
                .category(ToolCategory.COGNITION)
                .name("精确计算")
                .description("使用 BigDecimal 进行精确算术运算。支持四则运算(如 123.45+67.89)、百分比(如 200*15%)、日期差(如 2026-03-08 - 2025-01-01)")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("expression"),
                        "properties", Map.of(
                                "expression", Map.of("type", "string",
                                        "description", "数学表达式或日期差表达式")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    // ─────────────────────────────────────────────
    //  Shell 执行工具构建
    // ─────────────────────────────────────────────

    /** 构建 Shell 命令执行工具 — ProcessBuilder 子进程执行，HIGH 风险。 */
    private BuiltinTool buildShellExecTool(ShellExecToolExecutor executor) {
        return BuiltinTool.builder()
                .id("shell.exec")
                .category(ToolCategory.ACTION)
                .name("执行 Shell 命令")
                .description("在操作系统 Shell 中执行命令，捕获 stdout/stderr 输出。支持同步执行、后台执行和 yieldMs 自动后台化三种模式")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("command"),
                        "properties", Map.of(
                                "command", Map.of("type", "string",
                                        "description", "要执行的 Shell 命令"),
                                "workingDirectory", Map.of("type", "string",
                                        "description", "工作目录路径，默认使用当前进程工作目录"),
                                "timeoutSeconds", Map.of("type", "integer",
                                        "description", "命令超时时间（秒），默认 120"),
                                "background", Map.of("type", "boolean",
                                        "description", "立即后台执行，返回 sessionId，通过 process.* 工具管理进程"),
                                "yieldMs", Map.of("type", "integer",
                                        "description", "同步等待毫秒数，超时后自动转后台（0=立即后台，默认不启用）。适合不确定耗时的命令"),
                                "pty", Map.of("type", "boolean",
                                        "description", "分配伪终端（PTY），用于交互式 CLI（如 npm init、vim）。Unix 下通过 script 命令实现")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.workspacePaths("workingDirectory", "cwd")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    // ─────────────────────────────────────────────
    //  HTTP 请求工具构建
    // ─────────────────────────────────────────────

    /** 构建 HTTP 请求工具 — 支持 GET/POST/PUT/DELETE/PATCH，MEDIUM 风险。 */
    private BuiltinTool buildHttpRequestTool(HttpRequestToolExecutor executor) {
        return BuiltinTool.builder()
                .id("http.request")
                .category(ToolCategory.ACTION)
                .name("HTTP 请求")
                .description("发送 HTTP 请求到外部 API，支持 GET/POST/PUT/DELETE/PATCH 方法。禁止访问内网地址")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("url"),
                        "properties", Map.of(
                                "url", Map.of("type", "string",
                                        "description", "请求 URL"),
                                "method", Map.of("type", "string",
                                        "description", "HTTP 方法（GET/POST/PUT/DELETE/PATCH），默认 GET"),
                                "headers", Map.of("type", "object",
                                        "description", "请求头 Map"),
                                "body", Map.of("type", "string",
                                        "description", "请求体（POST/PUT/PATCH 时使用）"),
                                "timeoutSeconds", Map.of("type", "integer",
                                        "description", "请求超时时间（秒），默认 30")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.HTTP_REQUEST,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.origins("url")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    // ─────────────────────────────────────────────
    //  代码执行工具构建
    // ─────────────────────────────────────────────

    /** 构建代码执行工具 — 桥接 SandboxBooter，HIGH 风险。 */
    private BuiltinTool buildCodeExecuteTool(CodeExecuteToolExecutor executor) {
        return BuiltinTool.builder()
                .id("code.execute")
                .category(ToolCategory.ACTION)
                .name("执行代码")
                .description("在沙箱环境中执行代码，支持 Python/JavaScript/Shell。HIGH 风险，每次执行需用户确认")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("code"),
                        "properties", Map.of(
                                "code", Map.of("type", "string",
                                        "description", "要执行的代码"),
                                "language", Map.of("type", "string",
                                        "description", "编程语言（python/javascript/shell），默认使用配置值"),
                                "timeoutSeconds", Map.of("type", "integer",
                                        "description", "执行超时时间（秒），默认 30")
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
                .executor(executor::execute)
                .build();
    }

}
