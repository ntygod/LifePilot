package com.lifepilot.meta.infra;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelOperationDispatcher;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
import com.lifepilot.meta.infra.browser.BrowserToolProvider;
import com.lifepilot.meta.infra.browser.InteractiveElementIndexer;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.meta.infra.channel.ChannelToolProvider;
import com.lifepilot.meta.infra.code.CodeToolProvider;
import com.lifepilot.meta.infra.code.kernel.CodeKernelToolProvider;
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
import com.lifepilot.meta.infra.attachment.AttachmentToolProvider;
import com.lifepilot.meta.infra.shell.session.TmuxSessionManager;
import com.lifepilot.meta.infra.transcript.TranscriptToolProvider;
import com.lifepilot.meta.infra.interaction.NotifyToolProvider;
import com.lifepilot.meta.infra.web.SsrfGuard;
import com.lifepilot.meta.infra.web.WebSearchConfigProvider;
import com.lifepilot.meta.infra.web.WebToolProvider;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.meta.infra.task.TaskToolProvider;
import com.lifepilot.sandbox.guard.CommandGuard;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.validation.SkillPathWhitelist;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基础工具提供者 — 编排各子 ToolProvider，统一注册到 DynamicToolRegistry。
 *
 * <p>本类不直接构建任何工具，仅负责：创建子 Provider → 委托构建 → 注册。
 * 各子 Provider 自行决定工具暴露条件：{@link CodeToolProvider} / {@link CodeKernelToolProvider}
 * 受 {@code lifepilot.sandbox.enabled} 开关与 {@link PythonRuntimeManager} 注入控制，
 * 其余按 {@link Nullable} 依赖是否注入按需注册（如 channel / cron / browser session 不存在时静默跳过）。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class InfraToolProvider {

    private static final Logger log = LoggerFactory.getLogger(InfraToolProvider.class);

    private final MetaProperties properties;
    private final WebSearchConfigProvider webSearchConfigProvider;
    @Nullable private final SandboxSessionManager sandboxSessionManager;
    @Nullable private final CodeValidator codeValidator;
    @Nullable private final SandboxRepository sandboxRepository;
    @Nullable private final BrowserSessionManager browserSessionManager;
    @Nullable private final NotificationService notificationService;
    @Nullable private final CronTaskRepository cronTaskRepository;
    @Nullable private final CronScheduler cronScheduler;
    @Nullable private final NotificationProperties notificationProperties;
    @Nullable private final BackgroundProcessManager backgroundProcessManager;
    @Nullable private final ChannelRegistry channelRegistry;
    @Nullable private final ChannelOperationDispatcher channelOperationDispatcher;
    @Nullable private final ChannelDeliveryDispatcher channelDeliveryDispatcher;
    @Nullable private final ChannelInstanceService channelInstanceService;
    private final WorkspaceResolver workspaceResolver;
    @Nullable private final AttachmentRepository attachmentRepository;
    @Nullable private final ChatSessionRepository chatSessionRepository;
    @Nullable private final SkillPathWhitelist skillPathWhitelist;
    private final SsrfGuard ssrfGuard;
    private final InteractiveElementIndexer interactiveElementIndexer;
    @Nullable private final PythonRuntimeManager pythonRuntimeManager;
    @Nullable private final CommandGuard commandGuard;
    @Nullable private final SessionTranscriptRepository sessionTranscriptRepository;

    public InfraToolProvider(MetaProperties properties,
                             WebSearchConfigProvider webSearchConfigProvider,
                             @Nullable SandboxSessionManager sandboxSessionManager,
                             @Nullable CodeValidator codeValidator,
                             @Nullable SandboxRepository sandboxRepository,
                             @Nullable BrowserSessionManager browserSessionManager,
                             @Nullable NotificationService notificationService,
                             @Nullable CronTaskRepository cronTaskRepository,
                             @Nullable CronScheduler cronScheduler,
                             @Nullable NotificationProperties notificationProperties,
                             @Nullable BackgroundProcessManager backgroundProcessManager,
                             @Nullable ChannelRegistry channelRegistry,
                             @Nullable ChannelOperationDispatcher channelOperationDispatcher,
                             @Nullable ChannelDeliveryDispatcher channelDeliveryDispatcher,
                             @Nullable ChannelInstanceService channelInstanceService,
                             WorkspaceResolver workspaceResolver,
                             @Nullable AttachmentRepository attachmentRepository,
                             @Nullable ChatSessionRepository chatSessionRepository,
                             @Nullable SkillPathWhitelist skillPathWhitelist,
                             SsrfGuard ssrfGuard,
                             InteractiveElementIndexer interactiveElementIndexer,
                             @Nullable PythonRuntimeManager pythonRuntimeManager,
                             @Nullable CommandGuard commandGuard,
                             @Nullable SessionTranscriptRepository sessionTranscriptRepository) {
        this.properties = properties;
        this.webSearchConfigProvider = webSearchConfigProvider;
        this.sandboxSessionManager = sandboxSessionManager;
        this.codeValidator = codeValidator;
        this.sandboxRepository = sandboxRepository;
        this.browserSessionManager = browserSessionManager;
        this.notificationService = notificationService;
        this.cronTaskRepository = cronTaskRepository;
        this.cronScheduler = cronScheduler;
        this.notificationProperties = notificationProperties;
        this.backgroundProcessManager = backgroundProcessManager;
        this.channelRegistry = channelRegistry;
        this.channelOperationDispatcher = channelOperationDispatcher;
        this.channelDeliveryDispatcher = channelDeliveryDispatcher;
        this.channelInstanceService = channelInstanceService;
        this.workspaceResolver = workspaceResolver;
        this.attachmentRepository = attachmentRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.skillPathWhitelist = skillPathWhitelist;
        this.ssrfGuard = ssrfGuard;
        this.interactiveElementIndexer = interactiveElementIndexer;
        this.pythonRuntimeManager = pythonRuntimeManager;
        this.commandGuard = commandGuard;
        this.sessionTranscriptRepository = sessionTranscriptRepository;
    }

    /**
     * 注册基础工具到 DynamicToolRegistry。
     *
     * @param toolRegistry 动态工具注册中心
     */
    public void registerTools(DynamicToolRegistry toolRegistry) {
        int totalTools = 0;

        // Web 工具（web.search + web.fetch）
        var webToolProvider = new WebToolProvider(properties, webSearchConfigProvider, browserSessionManager, ssrfGuard);
        totalTools += registerBuiltinTools(toolRegistry, webToolProvider.buildWebTools());

        // 浏览器自动化工具
        var browserToolProvider = new BrowserToolProvider(browserSessionManager, properties, interactiveElementIndexer);
        totalTools += registerBuiltinTools(toolRegistry, browserToolProvider.buildBrowserTools());

        // 文件系统工具
        var fileEditConfig = properties.getInfra().getFileEdit();
        var editHistory = new FileEditHistory(
                fileEditConfig.getUndoMaxDepth(),
                fileEditConfig.getMaxSnapshotSizeBytes());
        var lintHook = new LintHookExecutor();
        var fileToolProvider = new FileToolProvider(properties, editHistory, lintHook, attachmentRepository, skillPathWhitelist);
        totalTools += registerBuiltinTools(toolRegistry, fileToolProvider.buildFileTools());

        // Transcript 检索工具 —— 让 LLM 主动取压缩前的历史工具调用结果，避免重跑
        if (sessionTranscriptRepository != null) {
            var transcriptToolProvider = new TranscriptToolProvider(sessionTranscriptRepository);
            totalTools += registerBuiltinTools(toolRegistry, transcriptToolProvider.buildTranscriptTools());
        } else {
            log.warn("SessionTranscriptRepository 不可用（如非 Web 上下文），跳过 transcript 工具注册");
        }

        // 附件登记工具 —— LLM 把工具产物（图片/docx/pdf）挂载为对话附件
        if (attachmentRepository != null) {
            var attachmentToolProvider = new AttachmentToolProvider(attachmentRepository, workspaceResolver);
            totalTools += registerBuiltinTools(toolRegistry, attachmentToolProvider.buildAttachmentTools());
        } else {
            log.warn("AttachmentRepository 不可用，跳过附件登记工具注册");
        }

        // 通知工具
        if (notificationService != null && notificationProperties != null) {
            var notifyToolProvider = new NotifyToolProvider(notificationService, notificationProperties);
            totalTools += registerBuiltinTools(toolRegistry, notifyToolProvider.buildNotifyTools());
        } else {
            log.warn("NotificationService 不可用，跳过通知工具注册");
        }

        // 自主任务工具（创建路径会通过 ChatSessionRepository 反查当前会话的 projectId，
        // 填入新建任务的归属 —— Plan 2 Task A6）
        if (cronTaskRepository != null && cronScheduler != null) {
            var taskToolProvider = new TaskToolProvider(cronTaskRepository, cronScheduler, chatSessionRepository);
            totalTools += registerBuiltinTools(toolRegistry, taskToolProvider.buildCronTools());
        } else {
            log.warn("CronTaskRepository 或 CronScheduler 不可用，跳过自主任务工具注册");
        }

        // 通用渠道工具
        if (channelRegistry != null && channelOperationDispatcher != null
                && channelDeliveryDispatcher != null && channelInstanceService != null) {
            var channelToolProvider = new ChannelToolProvider(
                    channelRegistry, channelOperationDispatcher, channelDeliveryDispatcher, channelInstanceService);
            totalTools += registerBuiltinTools(toolRegistry, channelToolProvider.buildChannelTools());
        } else {
            log.warn("渠道组件不完整，跳过通用渠道工具注册");
        }

        // Git 工具
        var gitConfig = properties.getInfra().getGit();
        if (gitConfig.isEnabled()) {
            var gitCmd = new GitCommandExecutor(gitConfig);
            if (gitCmd.isGitAvailable()) {
                var gitToolProvider = new GitToolProvider(gitCmd, gitConfig);
                totalTools += registerBuiltinTools(toolRegistry, gitToolProvider.buildGitTools());
            } else {
                log.warn("git 不可用，跳过 Git 工具注册");
            }
        }

        // 提前创建 tmuxSessionManager，供 Shell 工具与代码内核共用
        TmuxSessionManager tmuxSessionManager = null;
        var shellSessionConfig = properties.getInfra().getShellSession();
        if (shellSessionConfig.isEnabled()) {
            var tmuxCmd = new TmuxCommandExecutor(shellSessionConfig.getExecTimeoutSeconds());
            if (tmuxCmd.isTmuxAvailable()) {
                tmuxSessionManager = new TmuxSessionManager(tmuxCmd, shellSessionConfig, workspaceResolver);
            } else {
                log.warn("tmux 不可用，Shell 持久会话能力将不可用");
            }
        }

        // Shell 工具（shell.exec + shell.process）— 注入 commandGuard 让 shell.exec 也走 HARDLINE/DANGEROUS 护栏
        var shellExecExecutor = new ShellExecToolExecutor(properties, backgroundProcessManager, workspaceResolver, commandGuard);
        var shellToolProvider = new ShellToolProvider(shellExecExecutor, backgroundProcessManager, tmuxSessionManager);
        totalTools += registerBuiltinTools(toolRegistry, shellToolProvider.buildShellTools());

        // 代码执行工具 — kernel 与 sandbox 共享同一捆绑 Python 路径
        // 仅在 PythonRuntimeManager 可用（lifepilot.sandbox.enabled=true）时注册 code.execute 与 code.kernel：
        // sandbox 禁用时 SandboxAutoConfiguration 不注册 PythonRuntimeManager bean，
        // 此时强行构建 PersistentKernelManager 会触发 Objects.requireNonNull NPE，
        // 让整个 Spring Context 启动失败 —— 对面向大众用户的产品不可接受。
        // 合理产品降级：sandbox 禁用本就意味着用户不要代码执行能力，跳过这两类工具即可。
        if (pythonRuntimeManager != null) {
            PersistentKernelManager kernelManager = null;
            var kernelConfig = properties.getInfra().getKernel();
            if (kernelConfig.isEnabled()) {
                kernelManager = new PersistentKernelManager(kernelConfig, tmuxSessionManager, pythonRuntimeManager);
            }
            var codeToolProvider = new CodeToolProvider(
                    properties, sandboxSessionManager, codeValidator, sandboxRepository,
                    kernelManager, pythonRuntimeManager, commandGuard);
            totalTools += registerBuiltinTools(toolRegistry, codeToolProvider.buildCodeTools());

            // 代码内核管理工具（list / reset / inspect），仅在 kernel 启用时注册
            if (kernelManager != null) {
                var kernelToolProvider = new CodeKernelToolProvider(kernelManager);
                totalTools += registerBuiltinTools(toolRegistry, kernelToolProvider.buildKernelTools());
            }
        } else {
            log.info("Sandbox 已禁用（lifepilot.sandbox.enabled=false 或 PythonRuntimeManager bean 不可用），"
                    + "跳过代码执行（code.execute）与内核管理（code.kernel）工具注册");
        }

        log.info("基础工具注册完成: count={}", totalTools);
    }

    private int registerBuiltinTools(DynamicToolRegistry toolRegistry, List<BuiltinTool> tools) {
        tools.forEach(toolRegistry::registerBuiltinTool);
        return tools.size();
    }
}
