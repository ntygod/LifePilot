package com.lifepilot.meta.infra.web;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
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
 * Web 工具提供者。
 *
 * <p>集中管理 {@code web.search} 和 {@code web.fetch} 两个信息获取工具。</p>
 *
 * @author zsg
 * @since 2026-04-07
 */
public class WebToolProvider {

    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final MetaProperties properties;
    private final WebSearchConfigProvider webSearchConfigProvider;
    @Nullable
    private final BrowserSessionManager browserSessionManager;

    public WebToolProvider(MetaProperties properties,
                           WebSearchConfigProvider webSearchConfigProvider,
                           @Nullable BrowserSessionManager browserSessionManager) {
        this.properties = properties;
        this.webSearchConfigProvider = webSearchConfigProvider;
        this.browserSessionManager = browserSessionManager;
    }

    /**
     * 构建 Web 工具列表。
     *
     * @return Web 工具列表
     */
    public List<BuiltinTool> buildWebTools() {
        var webSearchExecutor = new WebSearchToolExecutor(webSearchConfigProvider);
        var webFetchExecutor = new WebFetchToolExecutor(properties, browserSessionManager);
        return List.of(
                buildWebSearchTool(webSearchExecutor),
                buildWebFetchTool(webFetchExecutor)
        );
    }

    /** 构建 Web 搜索工具。 */
    private BuiltinTool buildWebSearchTool(WebSearchToolExecutor executor) {
        return BuiltinTool.builder()
                .id("web.search")
                .category(ToolCategory.PERCEPTION)
                .name("Web 搜索")
                .description("搜索互联网信息。知识不足或用户要求联网搜索时使用。" +
                        "返回标题、URL 和摘要。需要获取完整页面内容时用 web.fetch。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("query"),
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "搜索关键词"),
                                "maxResults", Map.of("type", "integer",
                                        "description", "最大返回结果数，默认使用配置值"),
                                "offset", Map.of("type", "integer",
                                        "description", "分页偏移量，默认 0"),
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
                .description("抓取 URL 内容或调用外部 REST API。" +
                        "默认 GET 并提取正文，支持 CSS 选择器定向提取。" +
                        "多步 JS 交互请用 browser。禁止访问内网地址。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("url"),
                        "properties", Map.ofEntries(
                                Map.entry("url", Map.of("type", "string",
                                        "description", "目标网页 URL 或 API 地址")),
                                Map.entry("method", Map.of("type", "string",
                                        "description", "HTTP 方法（GET/POST/PUT/DELETE/PATCH），默认 GET")),
                                Map.entry("headers", Map.of("type", "object",
                                        "description", "请求头 Map")),
                                Map.entry("body", Map.of("type", "string",
                                        "description", "请求体（POST/PUT/PATCH 时使用）")),
                                Map.entry("selector", Map.of("type", "string",
                                        "description", "CSS 选择器，提取页面特定区域（仅 GET 有效）")),
                                Map.entry("renderJs", Map.of("type", "boolean",
                                        "description", "强制浏览器渲染（JS 动态页面），默认 false")),
                                Map.entry("timeoutSeconds", Map.of("type", "integer",
                                        "description", "请求超时秒数，默认 30"))
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
}
