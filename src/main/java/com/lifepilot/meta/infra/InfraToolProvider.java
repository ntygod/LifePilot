package com.lifepilot.meta.infra;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
import com.lifepilot.meta.infra.browser.BrowserToolProvider;
import com.lifepilot.meta.infra.code.CodeExecuteToolExecutor;
import com.lifepilot.meta.infra.file.FileToolProvider;
import com.lifepilot.meta.infra.env.DateTimeToolExecutor;
import com.lifepilot.meta.infra.env.SystemInfoToolExecutor;
import com.lifepilot.meta.infra.env.UserProfileToolExecutor;
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
 * 当前注册环境感知工具（3 个），后续任务将逐步添加其他类别工具。</p>
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
        // 环境感知工具（3 个）
        var dateTimeExecutor = new DateTimeToolExecutor(properties);
        var userProfileExecutor = new UserProfileToolExecutor(properties);
        var systemInfoExecutor = new SystemInfoToolExecutor();

        toolRegistry.registerBuiltinTool(buildDateTimeTool(dateTimeExecutor));
        toolRegistry.registerBuiltinTool(buildUserProfileTool(userProfileExecutor));
        toolRegistry.registerBuiltinTool(buildSystemInfoTool(systemInfoExecutor));

        // 信息获取工具（2 个）
        var webSearchExecutor = new WebSearchToolExecutor(webSearchConfigProvider);
        var webFetchExecutor = new WebFetchToolExecutor(properties);

        toolRegistry.registerBuiltinTool(buildWebSearchTool(webSearchExecutor));
        toolRegistry.registerBuiltinTool(buildWebFetchTool(webFetchExecutor));

        // HTTP 请求工具（1 个）
        var httpRequestExecutor = new HttpRequestToolExecutor();
        toolRegistry.registerBuiltinTool(buildHttpRequestTool(httpRequestExecutor));

        // 推理辅助工具（1 个）
        var calculateExecutor = new CalculateToolExecutor();

        toolRegistry.registerBuiltinTool(buildCalculateTool(calculateExecutor));

        // Shell 执行工具（1 个）
        var shellExecExecutor = new ShellExecToolExecutor(properties, backgroundProcessManager);

        toolRegistry.registerBuiltinTool(buildShellExecTool(shellExecExecutor));

        // 浏览器自动化工具（13 个，委托给 BrowserToolProvider）
        var browserToolProvider = new BrowserToolProvider(browserSessionManager, properties);
        browserToolProvider.buildBrowserTools().forEach(toolRegistry::registerBuiltinTool);

        // 代码执行工具（1 个）
        var codeExecuteExecutor = new CodeExecuteToolExecutor(properties, sandboxSessionManager, codeValidator, sandboxRepository);

        toolRegistry.registerBuiltinTool(buildCodeExecuteTool(codeExecuteExecutor));

        // 文件系统工具（委托给 FileToolProvider）
        var fileToolProvider = new FileToolProvider(properties);
        fileToolProvider.buildFileTools().forEach(toolRegistry::registerBuiltinTool);

        // 交互控制工具（3 个，委托给 InteractionToolProvider）
        if (interactionBridge != null && notificationService != null) {
            var interactionToolProvider = new InteractionToolProvider(interactionBridge, notificationService, notificationProperties);
            interactionToolProvider.buildInteractionTools().forEach(toolRegistry::registerBuiltinTool);
        } else {
            log.warn("InteractionBridge 或 NotificationService 不可用，跳过交互控制工具注册");
        }

        // 工作流管理工具（4 个，委托给 WorkflowToolProvider）
        if (workflowRegistry != null && workflowCommandService != null) {
            var workflowToolProvider = new WorkflowToolProvider(workflowRegistry, workflowCommandService);
            workflowToolProvider.buildWorkflowTools().forEach(toolRegistry::registerBuiltinTool);
            log.info("工作流管理工具注册完成: count=4");
        } else {
            log.warn("WorkflowRegistry 或 WorkflowCommandService 不可用，跳过工作流管理工具注册");
        }

        // 自主任务工具（6 个，委托给 TaskToolProvider）
        if (cronTaskRepository != null && cronScheduler != null && agentConfigProperties != null) {
            var taskToolProvider = new TaskToolProvider(cronTaskRepository, cronScheduler, agentConfigProperties);
            taskToolProvider.buildCronTools().forEach(toolRegistry::registerBuiltinTool);
            taskToolProvider.buildHeartbeatTools().forEach(toolRegistry::registerBuiltinTool);
            log.info("自主任务工具注册完成: count=6, categories=[cron, heartbeat]");
        } else {
            log.warn("CronTaskRepository 或 CronScheduler 不可用，跳过自主任务工具注册");
        }

        // 后台进程管理工具（4 个，委托给 ProcessToolProvider）
        if (backgroundProcessManager != null) {
            var processToolProvider = new ProcessToolProvider(backgroundProcessManager);
            processToolProvider.buildProcessTools().forEach(toolRegistry::registerBuiltinTool);
            log.info("后台进程管理工具注册完成: count=4");
        } else {
            log.warn("BackgroundProcessManager 不可用，跳过后台进程管理工具注册");
        }

        int totalTools = 28; // 基础工具
        if (interactionBridge != null && notificationService != null) totalTools += 3;
        if (workflowRegistry != null && workflowCommandService != null) totalTools += 4;
        if (cronTaskRepository != null && cronScheduler != null && agentConfigProperties != null) totalTools += 6;
        if (backgroundProcessManager != null) totalTools += 4;
        log.info("基础工具注册完成: count={}, categories=[env, web, reason, shell, browser, code, file, interact, workflow, task, process]",
                totalTools);
    }

    // ─────────────────────────────────────────────
    //  环境感知工具构建
    // ─────────────────────────────────────────────

    /** 构建日期时间工具。 */
    private BuiltinTool buildDateTimeTool(DateTimeToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.env.datetime")
                .category(ToolCategory.PERCEPTION)
                .name("获取当前日期时间")
                .description("获取当前日期、时间、星期和时区信息，可选覆盖时区")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "timezone", Map.of("type", "string",
                                        "description", "时区 ID（如 Asia/Shanghai），不传则使用用户配置或系统时区")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建用户画像工具。 */
    private BuiltinTool buildUserProfileTool(UserProfileToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.env.user-profile")
                .category(ToolCategory.PERCEPTION)
                .name("获取用户偏好")
                .description("获取用户偏好配置，包括时区、缓存 TTL 等信息")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建系统信息工具。 */
    private BuiltinTool buildSystemInfoTool(SystemInfoToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.env.system-info")
                .category(ToolCategory.PERCEPTION)
                .name("获取系统信息")
                .description("获取操作系统、JVM 版本、可用内存和磁盘空间等系统信息")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    // ─────────────────────────────────────────────
    //  信息获取工具构建
    // ─────────────────────────────────────────────

    /** 构建 Web 搜索工具。 */
    private BuiltinTool buildWebSearchTool(WebSearchToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.web.search")
                .category(ToolCategory.PERCEPTION)
                .name("Web 搜索")
                .description("通过 Tavily 联网检索信息，返回标题、摘要和链接列表，可附带 AI 生成的答案摘要")
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
                .id("builtin.web.fetch")
                .category(ToolCategory.PERCEPTION)
                .name("Web 页面抓取")
                .description("抓取指定 URL 的网页内容，解析 HTML 提取正文文本。支持 CSS 选择器定向提取")
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
                .id("builtin.reason.calculate")
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
                .id("builtin.shell.exec")
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
                .id("builtin.http.request")
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
                .id("builtin.code.execute")
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
