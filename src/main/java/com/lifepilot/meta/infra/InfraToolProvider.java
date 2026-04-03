package com.lifepilot.meta.infra;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelOperationDispatcher;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
import com.lifepilot.meta.infra.browser.BrowserToolProvider;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.meta.infra.channel.ChannelToolProvider;
import com.lifepilot.meta.infra.code.CodeExecuteToolExecutor;
import com.lifepilot.meta.infra.code.kernel.PersistentKernelManager;
import com.lifepilot.meta.infra.file.FileToolProvider;
import com.lifepilot.meta.infra.file.history.FileEditHistory;
import com.lifepilot.meta.infra.file.history.LintHookExecutor;
import com.lifepilot.meta.infra.git.GitCommandExecutor;
import com.lifepilot.meta.infra.git.GitToolProvider;
import com.lifepilot.meta.infra.shell.BackgroundProcessManager;
import com.lifepilot.meta.infra.shell.ShellExecToolExecutor;
import com.lifepilot.meta.infra.shell.ShellToolProvider;
import com.lifepilot.meta.infra.shell.session.TmuxCommandExecutor;
import com.lifepilot.meta.infra.shell.session.TmuxSessionManager;
import com.lifepilot.meta.infra.interaction.InteractionBridge;
import com.lifepilot.meta.infra.interaction.InteractionToolProvider;
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
    private final NotificationProperties notificationProperties;
    @Nullable
    private final BackgroundProcessManager backgroundProcessManager;
    @Nullable
    private final ChannelRegistry channelRegistry;
    @Nullable
    private final ChannelOperationDispatcher channelOperationDispatcher;
    @Nullable
    private final ChannelDeliveryDispatcher channelDeliveryDispatcher;
    @Nullable
    private final ChannelInstanceService channelInstanceService;

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
                             @Nullable NotificationProperties notificationProperties,
                             @Nullable BackgroundProcessManager backgroundProcessManager,
                             @Nullable ChannelRegistry channelRegistry,
                             @Nullable ChannelOperationDispatcher channelOperationDispatcher,
                             @Nullable ChannelDeliveryDispatcher channelDeliveryDispatcher,
                             @Nullable ChannelInstanceService channelInstanceService) {
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
        this.notificationProperties = notificationProperties;
        this.backgroundProcessManager = backgroundProcessManager;
        this.channelRegistry = channelRegistry;
        this.channelOperationDispatcher = channelOperationDispatcher;
        this.channelDeliveryDispatcher = channelDeliveryDispatcher;
        this.channelInstanceService = channelInstanceService;
    }

    /**
     * 注册基础工具到 DynamicToolRegistry。
     *
     * @param toolRegistry 动态工具注册中心
     */
    public void registerTools(DynamicToolRegistry toolRegistry) {
        // 信息获取工具
        var webSearchExecutor = new WebSearchToolExecutor(webSearchConfigProvider);
        var webFetchExecutor = new WebFetchToolExecutor(properties, browserSessionManager);
        int totalTools = registerBuiltinTools(toolRegistry, List.of(
                buildWebSearchTool(webSearchExecutor),
                buildWebFetchTool(webFetchExecutor)
        ));


        // 浏览器自动化工具（委托给 BrowserToolProvider）
        var browserToolProvider = new BrowserToolProvider(browserSessionManager, properties);
        totalTools += registerBuiltinTools(toolRegistry, browserToolProvider.buildBrowserTools());

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
        if (cronTaskRepository != null && cronScheduler != null) {
            var taskToolProvider = new TaskToolProvider(cronTaskRepository, cronScheduler);
            var cronTools = taskToolProvider.buildCronTools();
            totalTools += registerBuiltinTools(toolRegistry, cronTools);
            log.info("自主任务工具注册完成: count={}", cronTools.size());
        } else {
            log.warn("CronTaskRepository 或 CronScheduler 不可用，跳过自主任务工具注册");
        }

        // 通用渠道工具（基于插件描述动态生成）
        if (channelRegistry != null && channelOperationDispatcher != null
                && channelDeliveryDispatcher != null && channelInstanceService != null) {
            var channelToolProvider = new ChannelToolProvider(
                    channelRegistry, channelOperationDispatcher, channelDeliveryDispatcher, channelInstanceService);
            var channelTools = channelToolProvider.buildChannelTools();
            totalTools += registerBuiltinTools(toolRegistry, channelTools);
            log.info("通用渠道工具注册完成: count={}", channelTools.size());
        } else {
            log.warn("渠道组件不完整，跳过通用渠道工具注册");
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

        // 提前创建 tmuxSessionManager，供 shell 工具与代码内核共用
        TmuxSessionManager tmuxSessionManager = null;
        var shellSessionConfig = properties.getInfra().getShellSession();
        if (shellSessionConfig.isEnabled()) {
            var tmuxCmd = new TmuxCommandExecutor(shellSessionConfig.getExecTimeoutSeconds());
            if (tmuxCmd.isTmuxAvailable()) {
                tmuxSessionManager = new TmuxSessionManager(tmuxCmd, shellSessionConfig);
            } else {
                log.warn("tmux 不可用，Shell 持久会话能力将不可用");
            }
        }

        // Shell 工具（shell.exec + shell.process）
        var shellExecExecutor = new ShellExecToolExecutor(properties, backgroundProcessManager);
        var shellToolProvider = new ShellToolProvider(shellExecExecutor, backgroundProcessManager, tmuxSessionManager);
        var shellTools = shellToolProvider.buildShellTools();
        totalTools += registerBuiltinTools(toolRegistry, shellTools);
        log.info("Shell 工具注册完成: count={}", shellTools.size());

        // 持久代码内核（供代码执行工具路由使用，不再单独注册 kernel 工具）
        PersistentKernelManager kernelManager = null;
        var kernelConfig = properties.getInfra().getKernel();
        if (kernelConfig.isEnabled()) {
            kernelManager = new PersistentKernelManager(kernelConfig, tmuxSessionManager);
        }

        // 代码执行工具（支持持久内核路由）
        var codeExecuteExecutor = new CodeExecuteToolExecutor(properties, sandboxSessionManager, codeValidator, sandboxRepository, kernelManager);
        totalTools += registerBuiltinTools(toolRegistry, List.of(
                buildCodeExecuteTool(codeExecuteExecutor)
        ));

        log.info("基础工具注册完成: count={}, categories=[web, shell, browser, code, file, interact, workflow, task, channel, git]",
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
                .description("抓取指定 URL 的网页内容或发送 HTTP 请求到外部 API。" +
                        "当用户提供具体 URL 需要获取内容，或需要调用外部 REST API 时使用。" +
                        "默认 GET 请求并解析 HTML 提取正文文本，支持 CSS 选择器定向提取。" +
                        "可通过 method/headers/body 参数发送 POST/PUT/DELETE/PATCH 请求。" +
                        "当静态抓取内容为空或过短时自动回退到浏览器渲染。" +
                        "需要 JavaScript 渲染的多步交互操作请用 browser。禁止访问内网地址")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("url"),
                        "properties", Map.ofEntries(
                                Map.entry("url", Map.of("type", "string",
                                        "description", "目标网页 URL 或 API 地址")),
                                Map.entry("method", Map.of("type", "string",
                                        "description", "HTTP 方法（GET/POST/PUT/DELETE/PATCH），默认 GET")),
                                Map.entry("headers", Map.of("type", "object",
                                        "description", "请求头 Map（可选）")),
                                Map.entry("body", Map.of("type", "string",
                                        "description", "请求体（POST/PUT/PATCH 时使用）")),
                                Map.entry("selector", Map.of("type", "string",
                                        "description", "CSS 选择器，用于提取页面特定区域内容（可选，仅 GET 请求有效）")),
                                Map.entry("renderJs", Map.of("type", "boolean",
                                        "description", "强制使用浏览器渲染（适用于 JS 动态页面），默认 false")),
                                Map.entry("timeoutSeconds", Map.of("type", "integer",
                                        "description", "请求超时时间（秒），默认 30"))
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





    // ─────────────────────────────────────────────
    //  代码执行工具构建
    // ─────────────────────────────────────────────

    /** 构建代码执行工具 — 桥接 SandboxBooter，HIGH 风险。支持 kernelId 路由到持久内核。 */
    private BuiltinTool buildCodeExecuteTool(CodeExecuteToolExecutor executor) {
        return BuiltinTool.builder()
                .id("code.execute")
                .category(ToolCategory.ACTION)
                .name("执行代码")
                .description("在安全环境中执行代码，支持 Python/JavaScript/Shell。有两种执行模式：\n\n" +
                        "【一次性沙箱模式】（默认）：不传 kernelId，每次执行完全独立，变量不保留。" +
                        "适合运行一次性脚本、验证代码片段、执行系统命令等不需要上下文连续的场景。\n\n" +
                        "【持久内核模式】：传入 kernelId（如 \"data-analysis\" 或 \"debug-session\"），" +
                        "变量和导入在同一 kernelId 的多次调用之间保持。适合：" +
                        "(1) 数据分析——先 import pandas 读数据，后续多步处理同一个 DataFrame；" +
                        "(2) 多步调试——逐步排查问题，保留中间变量；" +
                        "(3) 环境搭建——先 %pip install 安装依赖，再 import 使用。" +
                        "同一个 kernelId 的多次调用共享状态，不同 kernelId 互相隔离。\n\n" +
                        "如果不确定是否需要持久内核，默认不传 kernelId 即可。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("code"),
                        "properties", Map.of(
                                "code", Map.of("type", "string",
                                        "description", "要执行的代码"),
                                "language", Map.of("type", "string",
                                        "description", "编程语言（python/javascript/shell），默认使用配置值"),
                                "timeoutSeconds", Map.of("type", "integer",
                                        "description", "执行超时时间（秒），默认 30"),
                                "kernelId", Map.of("type", "string",
                                        "description", "持久内核 ID（如 \"data-analysis\"、\"debug\"）。" +
                                                "传入后变量和导入跨调用保持，适合多步数据分析或调试。" +
                                                "同一 kernelId 共享状态，不同 kernelId 互相隔离。" +
                                                "不传则使用一次性沙箱。传入 'kernel:reset' 作为 code 值可清空指定 kernelId 的状态，" +
                                                "传入 'kernel:inspect' 作为 code 值可查看指定 kernelId 中的变量。")
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
