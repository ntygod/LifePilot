package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 浏览器工具提供者。
 *
 * <p>集中管理统一的 {@code browser} 元能力工具。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserToolProvider {

    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    @Nullable
    private final BrowserSessionManager browserSessionManager;
    private final MetaProperties properties;

    public BrowserToolProvider(@Nullable BrowserSessionManager browserSessionManager,
                               MetaProperties properties) {
        this.browserSessionManager = browserSessionManager;
        this.properties = properties;
    }

    public List<BuiltinTool> buildBrowserTools() {
        var textSnapshotCleaner = new TextSnapshotCleaner(
                properties.getInfra().getBrowser().getTextSnapshotMaxLength());
        var executor = new BrowserActionDispatchExecutor(
                new BrowserNavigateToolExecutor(browserSessionManager, textSnapshotCleaner),
                new BrowserClickToolExecutor(browserSessionManager),
                new BrowserInputToolExecutor(browserSessionManager),
                new BrowserScreenshotToolExecutor(browserSessionManager),
                new BrowserScrollToolExecutor(browserSessionManager, properties),
                new BrowserWaitToolExecutor(browserSessionManager, properties),
                new BrowserHoverToolExecutor(browserSessionManager),
                new BrowserSelectToolExecutor(browserSessionManager),
                new BrowserKeyboardToolExecutor(browserSessionManager),
                new BrowserEvaluateToolExecutor(browserSessionManager, properties),
                new BrowserAccessibilityToolExecutor(browserSessionManager, properties),
                new BrowserTabToolExecutor(browserSessionManager),
                browserSessionManager
        );
        return List.of(buildBrowserTool(executor));
    }

    private BuiltinTool buildBrowserTool(BrowserActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("browser")
                .category(ToolCategory.ACTION)
                .name("浏览器自动化")
                .description("控制浏览器进行网页交互，适用于需要 JavaScript 渲染的动态页面或多步交互操作。" +
                        "简单抓取静态网页内容请用 web.fetch。通过 action 参数支持：" +
                        "navigate=导航到 URL 并获取文本快照，" +
                        "click=点击页面元素，input=填写表单，scroll=滚动页面，" +
                        "wait=等待元素出现或消失，hover=悬停，select=下拉选择，keyboard=按键或文本输入，" +
                        "screenshot=截取页面截图，evaluate=执行 JavaScript，" +
                        "accessibility=获取无障碍树，tab=管理多标签页，close=关闭会话。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("navigate", "click", "input", "scroll", "wait", "hover", "select", "keyboard", "screenshot", "evaluate", "accessibility", "tab", "close"),
                                        "description", "浏览器操作类型")),
                                Map.entry("url", Map.of("type", "string", "description", "action=navigate 时的目标 URL；action=tab 且 open 时的目标 URL")),
                                Map.entry("selector", Map.of("type", "string", "description", "click/input/scroll/wait/hover/select 时的 CSS 选择器")),
                                Map.entry("value", Map.of("type", "string", "description", "action=input/select 时的输入值或 option value")),
                                Map.entry("label", Map.of("type", "string", "description", "action=select 时的 option 可见文本")),
                                Map.entry("direction", Map.of("type", "string", "description", "action=scroll 时的滚动方向: up/down")),
                                Map.entry("pixels", Map.of("type", "integer", "description", "action=scroll 时的滚动像素数")),
                                Map.entry("state", Map.of("type", "string", "description", "action=wait 时的目标状态: visible/hidden/attached")),
                                Map.entry("timeout", Map.of("type", "integer", "description", "action=wait 时的超时秒数")),
                                Map.entry("key", Map.of("type", "string", "description", "action=keyboard 时的键名或组合键")),
                                Map.entry("text", Map.of("type", "string", "description", "action=keyboard 时逐字符输入的文本")),
                                Map.entry("type", Map.of("type", "string", "description", "action=keyboard 时的操作类型: key/text")),
                                Map.entry("fullPage", Map.of("type", "boolean", "description", "action=screenshot 时是否截取整页")),
                                Map.entry("expression", Map.of("type", "string", "description", "action=evaluate 时的 JavaScript 表达式")),
                                Map.entry("rootSelector", Map.of("type", "string", "description", "action=accessibility 时的子树根节点 CSS 选择器")),
                                Map.entry("maxDepth", Map.of("type", "integer", "description", "action=accessibility 时的最大深度")),
                                Map.entry("tabAction", Map.of("type", "string", "description", "action=tab 时的具体操作: open/switch/close/list")),
                                Map.entry("tabId", Map.of("type", "string", "description", "action=tab 时 switch/close 的标签页 ID")),
                                Map.entry("sessionId", Map.of("type", "string", "description", "浏览器会话 ID，默认 default"))
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.BROWSER_AUTOMATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(INFRA_TAGS)
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }
}
