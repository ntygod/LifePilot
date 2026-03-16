package com.lifepilot.meta.infra;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.browser.*;
import com.lifepilot.meta.infra.code.CodeExecuteToolExecutor;
import com.lifepilot.meta.infra.file.FileToolProvider;
import com.lifepilot.meta.infra.env.DateTimeToolExecutor;
import com.lifepilot.meta.infra.env.SystemInfoToolExecutor;
import com.lifepilot.meta.infra.env.UserProfileToolExecutor;
import com.lifepilot.meta.infra.reason.CalculateToolExecutor;
import com.lifepilot.meta.infra.reason.ThinkToolExecutor;
import com.lifepilot.meta.infra.shell.ShellExecToolExecutor;
import com.lifepilot.meta.infra.interaction.*;
import com.lifepilot.meta.infra.web.WebFetchToolExecutor;
import com.lifepilot.meta.infra.web.WebSearchToolExecutor;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.model.*;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

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
@BuiltinSkill(id = "builtin.infrastructure", order = 1)
public class InfraToolProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(InfraToolProvider.class);
    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final MetaProperties properties;
    private final RestClient.Builder restClientBuilder;
    @Nullable
    private final SandboxBooter sandboxBooter;
    @Nullable
    private final InteractionBridge interactionBridge;
    @Nullable
    private final BrowserSessionManager browserSessionManager;

    public InfraToolProvider(MetaProperties properties,
                             RestClient.Builder restClientBuilder,
                             @Nullable SandboxBooter sandboxBooter,
                             @Nullable InteractionBridge interactionBridge,
                             @Nullable BrowserSessionManager browserSessionManager) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
        this.sandboxBooter = sandboxBooter;
        this.interactionBridge = interactionBridge;
        this.browserSessionManager = browserSessionManager;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("builtin.infrastructure")
                .name("基础工具集")
                .description("Agent 通用执行能力工具集，包含环境感知、信息获取、推理辅助、Shell 执行、浏览器自动化、代码执行、文件系统和交互控制")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions("基础工具集提供 Agent 的通用执行能力，无需额外激活即可使用。")
                .suggestedTools(List.of(
                        "builtin.env.datetime",
                        "builtin.env.user-profile",
                        "builtin.env.system-info",
                        "builtin.web.search",
                        "builtin.web.fetch",
                        "builtin.reason.think",
                        "builtin.reason.calculate",
                        "builtin.shell.exec",
                        "builtin.browser.navigate",
                        "builtin.browser.click",
                        "builtin.browser.input",
                        "builtin.browser.screenshot",
                        "builtin.code.execute",
                        "builtin.file.read",
                        "builtin.file.write",
                        "builtin.file.list",
                        "builtin.file.search",
                        "builtin.file.append",
                        "builtin.file.delete",
                        "builtin.file.copy",
                        "builtin.file.move",
                        "builtin.file.info",
                        "builtin.file.patch",
                        "builtin.interact.choose",
                        "builtin.interact.input",
                        "builtin.interact.notify"
                ))
                .metadata(Map.of())
                .build();
    }

    @Override
    public void registerTools(DynamicToolRegistry toolRegistry) {
        // 环境感知工具（3 个）
        var dateTimeExecutor = new DateTimeToolExecutor(properties);
        var userProfileExecutor = new UserProfileToolExecutor(properties);
        var systemInfoExecutor = new SystemInfoToolExecutor();

        toolRegistry.registerBuiltinTool(buildDateTimeTool(dateTimeExecutor));
        toolRegistry.registerBuiltinTool(buildUserProfileTool(userProfileExecutor));
        toolRegistry.registerBuiltinTool(buildSystemInfoTool(systemInfoExecutor));

        // 信息获取工具（2 个）
        var webSearchExecutor = new WebSearchToolExecutor(properties, restClientBuilder);
        var webFetchExecutor = new WebFetchToolExecutor(properties);

        toolRegistry.registerBuiltinTool(buildWebSearchTool(webSearchExecutor));
        toolRegistry.registerBuiltinTool(buildWebFetchTool(webFetchExecutor));

        // 推理辅助工具（2 个）
        var thinkExecutor = new ThinkToolExecutor();
        var calculateExecutor = new CalculateToolExecutor();

        toolRegistry.registerBuiltinTool(buildThinkTool(thinkExecutor));
        toolRegistry.registerBuiltinTool(buildCalculateTool(calculateExecutor));

        // Shell 执行工具（1 个）
        var shellExecExecutor = new ShellExecToolExecutor(properties);

        toolRegistry.registerBuiltinTool(buildShellExecTool(shellExecExecutor));

        // 浏览器自动化工具（4 个）
        var textSnapshotCleaner = new TextSnapshotCleaner(
                properties.getInfra().getBrowser().getTextSnapshotMaxLength());
        var navigateExecutor = new BrowserNavigateToolExecutor(browserSessionManager, textSnapshotCleaner);
        var clickExecutor = new BrowserClickToolExecutor(browserSessionManager);
        var inputExecutor = new BrowserInputToolExecutor(browserSessionManager);
        var screenshotExecutor = new BrowserScreenshotToolExecutor(browserSessionManager);

        toolRegistry.registerBuiltinTool(buildBrowserNavigateTool(navigateExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserClickTool(clickExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserInputTool(inputExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserScreenshotTool(screenshotExecutor));

        // 浏览器自动化扩展工具（9 个）
        var scrollExecutor = new BrowserScrollToolExecutor(browserSessionManager, properties);
        var waitExecutor = new BrowserWaitToolExecutor(browserSessionManager, properties);
        var hoverExecutor = new BrowserHoverToolExecutor(browserSessionManager);
        var selectExecutor = new BrowserSelectToolExecutor(browserSessionManager);
        var keyboardExecutor = new BrowserKeyboardToolExecutor(browserSessionManager);
        var evaluateExecutor = new BrowserEvaluateToolExecutor(browserSessionManager, properties);
        var accessibilityExecutor = new BrowserAccessibilityToolExecutor(browserSessionManager, properties);
        var tabExecutor = new BrowserTabToolExecutor(browserSessionManager);
        var storageExecutor = new BrowserStorageToolExecutor(browserSessionManager);

        toolRegistry.registerBuiltinTool(buildBrowserScrollTool(scrollExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserWaitTool(waitExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserHoverTool(hoverExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserSelectTool(selectExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserKeyboardTool(keyboardExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserEvaluateTool(evaluateExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserAccessibilityTool(accessibilityExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserTabTool(tabExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserStorageTool(storageExecutor));

        // 代码执行工具（1 个）
        var codeExecuteExecutor = new CodeExecuteToolExecutor(properties, sandboxBooter);

        toolRegistry.registerBuiltinTool(buildCodeExecuteTool(codeExecuteExecutor));

        // 文件系统工具（委托给 FileToolProvider）
        var fileToolProvider = new FileToolProvider(properties);
        fileToolProvider.buildFileTools().forEach(toolRegistry::registerBuiltinTool);

        // 交互控制工具（3 个）
        if (interactionBridge != null) {
            var interactChooseExecutor = new ChooseToolExecutor(interactionBridge);
            var interactInputExecutor = new InputToolExecutor(interactionBridge);
            var interactNotifyExecutor = new NotifyToolExecutor(interactionBridge);

            toolRegistry.registerBuiltinTool(buildChooseTool(interactChooseExecutor));
            toolRegistry.registerBuiltinTool(buildInputTool(interactInputExecutor));
            toolRegistry.registerBuiltinTool(buildNotifyTool(interactNotifyExecutor));
        } else {
            log.warn("InteractionBridge 不可用，跳过交互控制工具注册");
        }

        log.info("基础工具注册完成: count={}, categories=[env, web, reason, shell, browser, code, file, interact]",
                interactionBridge != null ? 35 : 32);
    }

    // ─────────────────────────────────────────────
    //  环境感知工具构建
    // ─────────────────────────────────────────────

    /** 构建日期时间工具。 */
    private BuiltinTool buildDateTimeTool(DateTimeToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.env.datetime")
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建用户画像工具。 */
    private BuiltinTool buildUserProfileTool(UserProfileToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.env.user-profile")
                .name("获取用户偏好")
                .description("获取用户偏好配置，包括时区、缓存 TTL 等信息")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建系统信息工具。 */
    private BuiltinTool buildSystemInfoTool(SystemInfoToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.env.system-info")
                .name("获取系统信息")
                .description("获取操作系统、JVM 版本、可用内存和磁盘空间等系统信息")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
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
                .name("Web 搜索")
                .description("通过搜索引擎检索信息，返回标题、摘要和链接列表。支持 DuckDuckGo（免费）/ Google / Bing")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "搜索关键词"),
                                "maxResults", Map.of("type", "integer",
                                        "description", "最大返回结果数，默认使用配置值")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建 Web 抓取工具。 */
    private BuiltinTool buildWebFetchTool(WebFetchToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.web.fetch")
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    // ─────────────────────────────────────────────
    //  推理辅助工具构建
    // ─────────────────────────────────────────────

    /** 构建思考工具 — Agent 内部推理草稿板。 */
    private BuiltinTool buildThinkTool(ThinkToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.reason.think")
                .name("思考")
                .description("Agent 内部推理草稿板，用于逐步思考复杂问题。内容不输出给用户，仅用于 Agent 的中间推理过程")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("reasoning"),
                        "properties", Map.of(
                                "reasoning", Map.of("type", "string",
                                        "description", "推理内容，Agent 的思考过程")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建计算工具 — BigDecimal 精确运算。 */
    private BuiltinTool buildCalculateTool(CalculateToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.reason.calculate")
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
                .name("执行 Shell 命令")
                .description("在操作系统 Shell 中执行命令，捕获 stdout/stderr 输出。支持安装软件、运行脚本、管理进程等系统操作。HIGH 风险，每次执行需用户确认")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("command"),
                        "properties", Map.of(
                                "command", Map.of("type", "string",
                                        "description", "要执行的 Shell 命令"),
                                "workingDirectory", Map.of("type", "string",
                                        "description", "工作目录路径，默认用户 home 目录"),
                                "timeoutSeconds", Map.of("type", "integer",
                                        "description", "命令超时时间（秒），默认使用配置值（30s）")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    // ─────────────────────────────────────────────
    //  浏览器自动化工具构建
    // ─────────────────────────────────────────────

    /** 构建浏览器导航工具 — Playwright page.navigate()，MEDIUM 风险。 */
    private BuiltinTool buildBrowserNavigateTool(BrowserNavigateToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.navigate")
                .name("浏览器导航")
                .description("使用浏览器导航到指定 URL，返回页面标题和文本快照。适用于访问 JavaScript 渲染的动态网页")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("url"),
                        "properties", Map.of(
                                "url", Map.of("type", "string",
                                        "description", "目标 URL"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default，同一会话复用 Page")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器点击工具 — Playwright page.click()，MEDIUM 风险。 */
    private BuiltinTool buildBrowserClickTool(BrowserClickToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.click")
                .name("浏览器点击")
                .description("点击页面中指定 CSS 选择器的元素，等待导航或响应完成")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("selector"),
                        "properties", Map.of(
                                "selector", Map.of("type", "string",
                                        "description", "CSS 选择器，定位要点击的元素"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器输入工具 — Playwright page.fill()，MEDIUM 风险。 */
    private BuiltinTool buildBrowserInputTool(BrowserInputToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.input")
                .name("浏览器输入")
                .description("在页面表单字段中填入文本内容，使用 CSS 选择器定位输入框")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("selector", "value"),
                        "properties", Map.of(
                                "selector", Map.of("type", "string",
                                        "description", "CSS 选择器，定位输入框元素"),
                                "value", Map.of("type", "string",
                                        "description", "要填入的文本内容"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器截图工具 — Playwright page.screenshot()，LOW 风险。 */
    private BuiltinTool buildBrowserScreenshotTool(BrowserScreenshotToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.screenshot")
                .name("浏览器截图")
                .description("截取当前页面截图，返回 Base64 编码的 PNG 图片。支持全页截图")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default"),
                                "fullPage", Map.of("type", "boolean",
                                        "description", "是否截取整页（包括滚动区域），默认 false")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    // ─────────────────────────────────────────────
    //  浏览器自动化扩展工具构建（9 个）
    // ─────────────────────────────────────────────

    /** 构建浏览器滚动工具 — 方向滚动或元素定位滚动，MEDIUM 风险。 */
    private BuiltinTool buildBrowserScrollTool(BrowserScrollToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.scroll")
                .name("浏览器滚动")
                .description("滚动页面或滚动到指定元素。支持方向滚动（up/down）和元素定位滚动")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "direction", Map.of("type", "string",
                                        "description", "滚动方向: up 或 down，默认 down"),
                                "pixels", Map.of("type", "integer",
                                        "description", "滚动像素数，默认使用配置值（500）"),
                                "selector", Map.of("type", "string",
                                        "description", "CSS 选择器，指定时滚动到该元素可见"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器等待工具 — 等待元素出现/消失，LOW 风险。 */
    private BuiltinTool buildBrowserWaitTool(BrowserWaitToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.wait")
                .name("浏览器等待")
                .description("等待页面中指定元素达到目标状态（visible/hidden/attached）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("selector"),
                        "properties", Map.of(
                                "selector", Map.of("type", "string",
                                        "description", "CSS 选择器"),
                                "state", Map.of("type", "string",
                                        "description", "目标状态: visible/hidden/attached，默认 visible"),
                                "timeout", Map.of("type", "integer",
                                        "description", "超时秒数，默认使用配置值（10s）"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器悬停工具 — 鼠标悬停到元素，MEDIUM 风险。 */
    private BuiltinTool buildBrowserHoverTool(BrowserHoverToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.hover")
                .name("浏览器悬停")
                .description("将鼠标悬停到指定 CSS 选择器的元素上，返回元素信息")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("selector"),
                        "properties", Map.of(
                                "selector", Map.of("type", "string",
                                        "description", "CSS 选择器，定位要悬停的元素"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器下拉选择工具 — select 元素选项选择，MEDIUM 风险。 */
    private BuiltinTool buildBrowserSelectTool(BrowserSelectToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.select")
                .name("浏览器下拉选择")
                .description("从 select 下拉元素中选择选项，支持按 value 或 label 选择")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("selector"),
                        "properties", Map.of(
                                "selector", Map.of("type", "string",
                                        "description", "CSS 选择器，定位 select 元素"),
                                "value", Map.of("type", "string",
                                        "description", "option value 值"),
                                "label", Map.of("type", "string",
                                        "description", "option 可见文本"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器键盘工具 — 按键或文本输入，MEDIUM 风险。 */
    private BuiltinTool buildBrowserKeyboardTool(BrowserKeyboardToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.keyboard")
                .name("浏览器键盘")
                .description("模拟键盘操作，支持单键/组合键按下和逐字符文本输入")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "key", Map.of("type", "string",
                                        "description", "键名或组合键（如 Enter、Control+A）"),
                                "text", Map.of("type", "string",
                                        "description", "要逐字符输入的文本"),
                                "type", Map.of("type", "string",
                                        "description", "操作类型: key（按键）或 text（文本输入），默认 key"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器 JS 执行工具 — 执行任意 JavaScript，HIGH 风险。 */
    private BuiltinTool buildBrowserEvaluateTool(BrowserEvaluateToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.evaluate")
                .name("浏览器 JS 执行")
                .description("在当前页面上下文中执行 JavaScript 表达式，返回 JSON 序列化结果。HIGH 风险")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("expression"),
                        "properties", Map.of(
                                "expression", Map.of("type", "string",
                                        "description", "JavaScript 表达式"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器无障碍树工具 — 获取页面无障碍树快照，LOW 风险。 */
    private BuiltinTool buildBrowserAccessibilityTool(BrowserAccessibilityToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.accessibility")
                .name("浏览器无障碍树")
                .description("获取页面或子树的无障碍树结构快照，用于理解页面语义结构")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "rootSelector", Map.of("type", "string",
                                        "description", "子树根节点 CSS 选择器，不传则返回整页"),
                                "maxDepth", Map.of("type", "integer",
                                        "description", "最大深度，默认使用配置值（5）"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器标签页管理工具 — 多标签页操作，MEDIUM 风险。 */
    private BuiltinTool buildBrowserTabTool(BrowserTabToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.tab")
                .name("浏览器标签页")
                .description("管理浏览器标签页，支持打开新标签页、切换、关闭和列出所有标签页")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.of(
                                "action", Map.of("type", "string",
                                        "description", "操作类型: open/switch/close/list"),
                                "url", Map.of("type", "string",
                                        "description", "open 时的目标 URL"),
                                "tabId", Map.of("type", "string",
                                        "description", "switch/close 时的标签页 ID"),
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器存储管理工具 — Cookie 和 localStorage 操作，MEDIUM 风险。 */
    private BuiltinTool buildBrowserStorageTool(BrowserStorageToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.browser.storage")
                .name("浏览器存储")
                .description("管理浏览器存储，支持 Cookie 和 localStorage 的读取、设置和清除")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("target", "action"),
                        "properties", Map.of(
                                "target", Map.of("type", "string",
                                        "description", "存储类型: cookie 或 localStorage"),
                                "action", Map.of("type", "string",
                                        "description", "操作类型: get/set/clear"),
                                "name", Map.of("type", "string",
                                        "description", "cookie name 或 localStorage key"),
                                "value", Map.of("type", "string",
                                        "description", "set 时的值")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    // ─────────────────────────────────────────────
    //  交互控制工具构建
    // ─────────────────────────────────────────────

    /** 构建选择工具 — 阻塞等待用户从选项列表中选择，LOW 风险。 */
    private BuiltinTool buildChooseTool(ChooseToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.interact.choose")
                .name("请求用户选择")
                .description("向用户展示选项列表并请求选择，阻塞等待用户响应")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("message", "options", "sessionId"),
                        "properties", Map.of(
                                "message", Map.of("type", "string",
                                        "description", "选择提示消息"),
                                "options", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "可选项列表"),
                                "sessionId", Map.of("type", "string",
                                        "description", "当前会话 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建输入工具 — 阻塞等待用户自由文本输入，LOW 风险。 */
    private BuiltinTool buildInputTool(InputToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.interact.input")
                .name("请求用户输入")
                .description("向用户展示输入提示并请求自由文本输入，阻塞等待用户响应")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("message", "sessionId"),
                        "properties", Map.of(
                                "message", Map.of("type", "string",
                                        "description", "输入提示消息"),
                                "sessionId", Map.of("type", "string",
                                        "description", "当前会话 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建通知工具 — 非阻塞推送通知消息，LOW 风险。 */
    private BuiltinTool buildNotifyTool(NotifyToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.interact.notify")
                .name("推送通知")
                .description("向用户推送通知消息，非阻塞（不等待用户响应）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("message", "sessionId"),
                        "properties", Map.of(
                                "message", Map.of("type", "string",
                                        "description", "通知消息内容"),
                                "sessionId", Map.of("type", "string",
                                        "description", "当前会话 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }
}
