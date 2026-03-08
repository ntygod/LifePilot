package com.lifepilot.meta.infra;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.browser.*;
import com.lifepilot.meta.infra.code.CodeExecuteToolExecutor;
import com.lifepilot.meta.infra.env.DateTimeToolExecutor;
import com.lifepilot.meta.infra.env.SystemInfoToolExecutor;
import com.lifepilot.meta.infra.env.UserProfileToolExecutor;
import com.lifepilot.meta.infra.reason.CalculateToolExecutor;
import com.lifepilot.meta.infra.reason.ThinkToolExecutor;
import com.lifepilot.meta.infra.shell.ShellExecToolExecutor;
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
    private final Object interactionBridge;
    @Nullable
    private final BrowserSessionManager browserSessionManager;

    public InfraToolProvider(MetaProperties properties,
                             RestClient.Builder restClientBuilder,
                             @Nullable SandboxBooter sandboxBooter,
                             @Nullable Object interactionBridge,
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
                .systemPrompt("基础工具集提供 Agent 的通用执行能力，无需额外激活即可使用。")
                .allowedTools(List.of(
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
                        "builtin.code.execute"
                ))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.LIGHTWEIGHT)
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
        var navigateExecutor = new BrowserNavigateToolExecutor(browserSessionManager);
        var clickExecutor = new BrowserClickToolExecutor(browserSessionManager);
        var inputExecutor = new BrowserInputToolExecutor(browserSessionManager);
        var screenshotExecutor = new BrowserScreenshotToolExecutor(browserSessionManager);

        toolRegistry.registerBuiltinTool(buildBrowserNavigateTool(navigateExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserClickTool(clickExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserInputTool(inputExecutor));
        toolRegistry.registerBuiltinTool(buildBrowserScreenshotTool(screenshotExecutor));

        // 代码执行工具（1 个）
        var codeExecuteExecutor = new CodeExecuteToolExecutor(properties, sandboxBooter);

        toolRegistry.registerBuiltinTool(buildCodeExecuteTool(codeExecuteExecutor));

        log.info("基础工具注册完成: count=13, categories=[env, web, reason, shell, browser, code]");
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
}
