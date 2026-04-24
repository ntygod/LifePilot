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
                browserSessionManager, properties, textSnapshotCleaner);
        return List.of(buildBrowserTool(executor));
    }

    private BuiltinTool buildBrowserTool(BrowserActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("browser")
                .category(ToolCategory.ACTION)
                .name("浏览器自动化")
                .description("Drive browser automation via Playwright. Actions: navigate/click/input/scroll/wait/hover/select/keyboard/screenshot/evaluate/accessibility/tab/storage/close.")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("navigate", "click", "input", "scroll", "wait", "hover",
                                                "select", "keyboard", "screenshot", "evaluate", "accessibility",
                                                "tab", "storage", "close"),
                                        "description", "浏览器操作类型。navigate 返回文本快照，screenshot 返回 Base64 图片")),
                                Map.entry("url", Map.of("type", "string",
                                        "description", "navigate 时的目标 URL；tab open 时的目标 URL")),
                                Map.entry("selector", Map.of("type", "string",
                                        "description", "CSS 选择器；click/input/scroll/wait/hover/select 使用")),
                                Map.entry("value", Map.of("type", "string",
                                        "description", "input/select 时的输入值或 option value")),
                                Map.entry("label", Map.of("type", "string",
                                        "description", "select 时按可见文本匹配")),
                                Map.entry("direction", Map.of("type", "string",
                                        "enum", List.of("up", "down"),
                                        "description", "scroll 方向")),
                                Map.entry("pixels", Map.of("type", "integer",
                                        "description", "scroll 像素数")),
                                Map.entry("state", Map.of("type", "string",
                                        "enum", List.of("visible", "hidden", "attached"),
                                        "description", "wait 目标状态")),
                                Map.entry("timeout", Map.of("type", "integer",
                                        "description", "wait 超时秒数")),
                                Map.entry("key", Map.of("type", "string",
                                        "description", "keyboard 键名或组合键（如 Enter、Control+A）")),
                                Map.entry("text", Map.of("type", "string",
                                        "description", "keyboard 逐字符输入文本")),
                                Map.entry("type", Map.of("type", "string",
                                        "enum", List.of("key", "text"),
                                        "description", "keyboard 操作类型")),
                                Map.entry("fullPage", Map.of("type", "boolean",
                                        "description", "screenshot 是否截取整页")),
                                Map.entry("expression", Map.of("type", "string",
                                        "description", "evaluate 时的 JavaScript 表达式")),
                                Map.entry("rootSelector", Map.of("type", "string",
                                        "description", "accessibility 子树根节点 CSS 选择器")),
                                Map.entry("maxDepth", Map.of("type", "integer",
                                        "description", "accessibility 最大深度")),
                                Map.entry("tabAction", Map.of("type", "string",
                                        "enum", List.of("open", "switch", "close", "list"),
                                        "description", "tab 具体操作")),
                                Map.entry("tabId", Map.of("type", "string",
                                        "description", "tab switch/close 的标签页 ID")),
                                Map.entry("target", Map.of("type", "string",
                                        "enum", List.of("cookie", "localStorage"),
                                        "description", "storage 操作目标")),
                                Map.entry("storageAction", Map.of("type", "string",
                                        "enum", List.of("get", "set", "clear"),
                                        "description", "storage 操作类型")),
                                Map.entry("name", Map.of("type", "string",
                                        "description", "storage set/get 时的键名")),
                                Map.entry("sessionId", Map.of("type", "string",
                                        "description", "浏览器会话 ID，默认 default")),
                                Map.entry("acquisitionMode", Map.of("type", "string",
                                        "enum", List.of("LAUNCH", "CDP", "PERSISTENT"),
                                        "description", "浏览器模式。LAUNCH=启动新浏览器（默认），CDP=连接已运行的 Chrome（需 cdpUrl），PERSISTENT=持久 profile（需 userDataDir）。仅首次创建会话生效")),
                                Map.entry("cdpUrl", Map.of("type", "string",
                                        "description", "CDP 模式的调试端口 URL，如 http://localhost:9222")),
                                Map.entry("userDataDir", Map.of("type", "string",
                                        "description", "PERSISTENT 模式的用户数据目录路径"))
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.BROWSER_AUTOMATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                ))
                .tags(List.of("infrastructure", "browser", "automation", "playwright", "navigate", "click", "screenshot", "web"))
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }
}
