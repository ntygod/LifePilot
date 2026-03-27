package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 浏览器工具提供者 — 构建所有浏览器自动化工具的 {@link BuiltinTool} 列表。
 *
 * <p>从 {@link com.lifepilot.meta.infra.InfraToolProvider} 中拆分出来，
 * 集中管理 13 个浏览器工具的注册逻辑。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserToolProvider {

    private static final List<String> INFRA_TAGS = List.of("infrastructure");
    private static final ToolExecutionSemantics BROWSER_SESSION_SEMANTICS = ToolExecutionSemantics.of(
            PermissionActionType.BROWSER_AUTOMATION,
            com.lifepilot.tool.model.ToolSchedulingMode.SEQUENTIAL,
            ToolScopeResolvers.exactValues("sessionIds", "sessionId")
    );

    @Nullable
    private final BrowserSessionManager browserSessionManager;
    private final MetaProperties properties;

    public BrowserToolProvider(@Nullable BrowserSessionManager browserSessionManager,
                               MetaProperties properties) {
        this.browserSessionManager = browserSessionManager;
        this.properties = properties;
    }

    /**
     * 构建所有浏览器工具的 BuiltinTool 列表（13 个）。
     *
     * @return 浏览器工具列表
     */
    public List<BuiltinTool> buildBrowserTools() {
        var tools = new ArrayList<BuiltinTool>();

        // 基础浏览器工具（4 个）
        var textSnapshotCleaner = new TextSnapshotCleaner(
                properties.getInfra().getBrowser().getTextSnapshotMaxLength());
        tools.add(buildBrowserNavigateTool(new BrowserNavigateToolExecutor(browserSessionManager, textSnapshotCleaner)));
        tools.add(buildBrowserClickTool(new BrowserClickToolExecutor(browserSessionManager)));
        tools.add(buildBrowserInputTool(new BrowserInputToolExecutor(browserSessionManager)));
        tools.add(buildBrowserScreenshotTool(new BrowserScreenshotToolExecutor(browserSessionManager)));

        // 扩展浏览器工具（9 个）
        tools.add(buildBrowserScrollTool(new BrowserScrollToolExecutor(browserSessionManager, properties)));
        tools.add(buildBrowserWaitTool(new BrowserWaitToolExecutor(browserSessionManager, properties)));
        tools.add(buildBrowserHoverTool(new BrowserHoverToolExecutor(browserSessionManager)));
        tools.add(buildBrowserSelectTool(new BrowserSelectToolExecutor(browserSessionManager)));
        tools.add(buildBrowserKeyboardTool(new BrowserKeyboardToolExecutor(browserSessionManager)));
        tools.add(buildBrowserEvaluateTool(new BrowserEvaluateToolExecutor(browserSessionManager, properties)));
        tools.add(buildBrowserAccessibilityTool(new BrowserAccessibilityToolExecutor(browserSessionManager, properties)));
        tools.add(buildBrowserTabTool(new BrowserTabToolExecutor(browserSessionManager)));
        tools.add(buildBrowserStorageTool(new BrowserStorageToolExecutor(browserSessionManager)));
        tools.add(buildBrowserCloseTool());

        return List.copyOf(tools);
    }

    // ─────────────────────────────────────────────
    //  基础浏览器工具构建（4 个）
    // ─────────────────────────────────────────────

    /** 构建浏览器导航工具 — Playwright page.navigate()，MEDIUM 风险。 */
    private BuiltinTool buildBrowserNavigateTool(BrowserNavigateToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.navigate")
                .category(ToolCategory.PERCEPTION)
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
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.BROWSER_AUTOMATION,
                        com.lifepilot.tool.model.ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.composite(
                                ToolScopeResolvers.origins("url"),
                                ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                        )
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器点击工具 — Playwright page.click()，MEDIUM 风险。 */
    private BuiltinTool buildBrowserClickTool(BrowserClickToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.click")
                .category(ToolCategory.ACTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器输入工具 — Playwright page.fill()，MEDIUM 风险。 */
    private BuiltinTool buildBrowserInputTool(BrowserInputToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.input")
                .category(ToolCategory.ACTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器截图工具 — Playwright page.screenshot()，LOW 风险。 */
    private BuiltinTool buildBrowserScreenshotTool(BrowserScreenshotToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.screenshot")
                .category(ToolCategory.PERCEPTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    // ─────────────────────────────────────────────
    //  扩展浏览器工具构建（9 个）
    // ─────────────────────────────────────────────

    /** 构建浏览器滚动工具 — 方向滚动或元素定位滚动，MEDIUM 风险。 */
    private BuiltinTool buildBrowserScrollTool(BrowserScrollToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.scroll")
                .category(ToolCategory.ACTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器等待工具 — 等待元素出现/消失，LOW 风险。 */
    private BuiltinTool buildBrowserWaitTool(BrowserWaitToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.wait")
                .category(ToolCategory.PERCEPTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器悬停工具 — 鼠标悬停到元素，MEDIUM 风险。 */
    private BuiltinTool buildBrowserHoverTool(BrowserHoverToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.hover")
                .category(ToolCategory.ACTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器下拉选择工具 — select 元素选项选择，MEDIUM 风险。 */
    private BuiltinTool buildBrowserSelectTool(BrowserSelectToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.select")
                .category(ToolCategory.ACTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器键盘工具 — 按键或文本输入，MEDIUM 风险。 */
    private BuiltinTool buildBrowserKeyboardTool(BrowserKeyboardToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.keyboard")
                .category(ToolCategory.ACTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器 JS 执行工具 — 执行任意 JavaScript，HIGH 风险。 */
    private BuiltinTool buildBrowserEvaluateTool(BrowserEvaluateToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.evaluate")
                .category(ToolCategory.ACTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器无障碍树工具 — 获取页面无障碍树快照，LOW 风险。 */
    private BuiltinTool buildBrowserAccessibilityTool(BrowserAccessibilityToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.accessibility")
                .category(ToolCategory.PERCEPTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器标签页管理工具 — 多标签页操作，MEDIUM 风险。 */
    private BuiltinTool buildBrowserTabTool(BrowserTabToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.tab")
                .category(ToolCategory.ACTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器存储管理工具 — Cookie 和 localStorage 操作，MEDIUM 风险。 */
    private BuiltinTool buildBrowserStorageTool(BrowserStorageToolExecutor executor) {
        return BuiltinTool.builder()
                .id("browser.storage")
                .category(ToolCategory.ACTION)
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
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建浏览器关闭工具。 */
    private BuiltinTool buildBrowserCloseTool() {
        return BuiltinTool.builder()
                .id("browser.close")
                .category(ToolCategory.ACTION)
                .name("关闭浏览器")
                .description("关闭当前浏览器会话，释放资源")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，不传则关闭默认会话")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(BROWSER_SESSION_SEMANTICS)
                .tags(INFRA_TAGS)
                .executor(input -> {
                    try {
                        String sessionId = input.getOptionalParam("sessionId", String.class)
                                .orElse("default");
                        browserSessionManager.closePage(sessionId);
                        return com.lifepilot.tool.model.ToolResult.success(Map.of(
                                "message", "浏览器会话已关闭: " + sessionId));
                    } catch (Exception e) {
                        return com.lifepilot.tool.model.ToolResult.error("关闭浏览器失败: " + e.getMessage());
                    }
                })
                .build();
    }
}
