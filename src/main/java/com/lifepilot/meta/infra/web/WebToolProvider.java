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

    private final MetaProperties properties;
    private final WebSearchConfigProvider webSearchConfigProvider;
    @Nullable
    private final BrowserSessionManager browserSessionManager;
    private final SsrfGuard ssrfGuard;

    public WebToolProvider(MetaProperties properties,
                           WebSearchConfigProvider webSearchConfigProvider,
                           @Nullable BrowserSessionManager browserSessionManager,
                           SsrfGuard ssrfGuard) {
        this.properties = properties;
        this.webSearchConfigProvider = webSearchConfigProvider;
        this.browserSessionManager = browserSessionManager;
        this.ssrfGuard = ssrfGuard;
    }

    /**
     * 构建 Web 工具列表。
     *
     * @return Web 工具列表
     */
    public List<BuiltinTool> buildWebTools() {
        var webSearchExecutor = new WebSearchToolExecutor(webSearchConfigProvider);
        var webFetchExecutor = new WebFetchToolExecutor(properties, browserSessionManager, ssrfGuard);
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
                .description("""
                        用关键词搜互联网，返回标题/URL/摘要。需要完整页面内容用 web_fetch，实时信息/新闻用 web_search 先搜再 fetch。
                        无按时间过滤参数——时效搜索在 query 中加年份或"最新""近期"等关键词。maxResults 控制返回数，offset 分页翻页。
                        搜不到时换英文关键词或不同表述重试。""")
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
                .tags(List.of("搜索", "互联网", "网络", "查询", "search", "web", "internet"))
                .executor(executor::execute)
                .build();
    }

    /** 构建 Web 抓取工具。 */
    private BuiltinTool buildWebFetchTool(WebFetchToolExecutor executor) {
        return BuiltinTool.builder()
                .id("web.fetch")
                .category(ToolCategory.PERCEPTION)
                .name("Web 页面抓取")
                .description("抓取 URL 内容或调外部 REST API。GET 自动判 HTML 解析（可 CSS 选择器），renderJs=true 走 headless 渲染；POST/PUT/DELETE 直接发请求。内网地址有 SSRF 守卫。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("url"),
                        "properties", Map.ofEntries(
                                Map.entry("url", Map.of("type", "string",
                                        "description", "目标网页 URL 或 API 地址")),
                                Map.entry("method", Map.of("type", "string",
                                        "description", "HTTP 方法（GET/POST/PUT/DELETE/PATCH），默认 GET；非 GET 不走 Jsoup/浏览器")),
                                Map.entry("headers", Map.of("type", "object",
                                        "description", "自定义请求头 Map（键值均为字符串），默认仅包含 User-Agent",
                                        "additionalProperties", Map.of("type", "string"))),
                                Map.entry("body", Map.of("type", "string",
                                        "description", "请求体（POST/PUT/PATCH 时使用），GET/DELETE 通常留空")),
                                Map.entry("selector", Map.of("type", "string",
                                        "description", "CSS 选择器，提取页面特定区域（仅 GET + HTML/XML 有效）")),
                                Map.entry("renderJs", Map.of("type", "boolean",
                                        "description", "强制浏览器渲染（JS 动态页面），默认 false；非 GET 忽略")),
                                Map.entry("timeoutSeconds", Map.of("type", "integer",
                                        "description", "请求超时秒数，默认使用 infra.web-fetch.timeout-seconds 配置"))
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.HTTP_REQUEST,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.origins("url")
                ))
                .tags(List.of("抓取", "网页", "下载", "请求", "接口", "fetch", "web", "http", "api"))
                .executor(executor::execute)
                .build();
    }
}
