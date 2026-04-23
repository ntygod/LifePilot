package com.lifepilot.tool.search;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 注册 tools.search / tools.describe / tools.list 三个 Meta BuiltinTool。
 *
 * <p>这三个工具必须常驻 prompt（在 Tier 1 pinned 列表）。
 * Executor 从 ToolInput 的 context 拿当前 ReactAgentState，传给服务层。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class BuiltinToolSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolSearchProvider.class);

    private final ToolSearchService searchService;
    private final ToolDescribeService describeService;
    private final ToolListService listService;

    public BuiltinToolSearchProvider(
            ToolSearchService searchService,
            ToolDescribeService describeService,
            ToolListService listService) {
        this.searchService = searchService;
        this.describeService = describeService;
        this.listService = listService;
    }

    /** 构建 tools.search 内置工具定义。 */
    public BuiltinTool searchTool() {
        return BuiltinTool.builder()
                .id("tools.search")
                .name("搜索工具")
                .description("Search the tool registry by English keywords and return top-k matches with BM25 ranking")
                .tags(List.of("search", "tools", "discover", "find", "registry"))
                .category(ToolCategory.INTROSPECTION)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "English keywords describing the desired capability"),
                                "category", Map.of("type", "string",
                                        "description", "Optional category filter: PERCEPTION/ACTION/COGNITION/STORAGE/INTERACTION/INTROSPECTION/EXTENSION"),
                                "limit", Map.of("type", "integer",
                                        "description", "Max results, default 5, max 20")
                        ),
                        "required", List.of("query")
                )))
                .executor(this::executeSearch)
                .build();
    }

    /** 构建 tools.describe 内置工具定义。 */
    public BuiltinTool describeTool() {
        return BuiltinTool.builder()
                .id("tools.describe")
                .name("查询工具详情")
                .description("Fetch full JSON schema and metadata for the specified tool IDs, batch supported")
                .tags(List.of("describe", "tools", "schema", "inspect", "registry"))
                .category(ToolCategory.INTROSPECTION)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "tool_ids", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "Array of tool IDs to describe")
                        ),
                        "required", List.of("tool_ids")
                )))
                .executor(this::executeDescribe)
                .build();
    }

    /** 构建 tools.list 内置工具定义。 */
    public BuiltinTool listTool() {
        return BuiltinTool.builder()
                .id("tools.list")
                .name("列举工具")
                .description("List tool IDs grouped by category, returns IDs only, call describe for full schema details")
                .tags(List.of("list", "tools", "browse", "enumerate", "registry"))
                .category(ToolCategory.INTROSPECTION)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "category", Map.of(
                                        "type", "string",
                                        "description", "Optional category filter; omit to list all")
                        )
                )))
                .executor(this::executeList)
                .build();
    }

    private ToolResult executeSearch(ToolInput input) {
        String query = input.getOptionalParam("query", String.class).orElse("");
        String category = input.getOptionalParam("category", String.class).orElse(null);
        Integer limit = input.getOptionalParam("limit", Integer.class).orElse(null);

        ReactAgentState state = extractState(input);

        ToolSearchResult result = searchService.search(state, query, category, limit);
        return ToolResult.success(Map.of(
                "results", result.results(),
                "total_matched", result.totalMatched(),
                "confidence", result.confidence().name(),
                "hint", result.hint() == null ? "" : result.hint()
        ));
    }

    @SuppressWarnings("unchecked")
    private ToolResult executeDescribe(ToolInput input) {
        List<String> ids = (List<String>) input.parameters().getOrDefault("tool_ids", List.of());
        ToolDescribeResult result = describeService.describe(ids);
        return ToolResult.success(Map.of(
                "schemas", result.schemas(),
                "not_found", result.notFound(),
                "suggestion", result.suggestion() == null ? "" : result.suggestion()
        ));
    }

    private ToolResult executeList(ToolInput input) {
        String category = input.getOptionalParam("category", String.class).orElse(null);
        ToolListResult result = listService.list(category);
        return ToolResult.success(Map.of(
                "categories", result.categories(),
                "total", result.total()
        ));
    }

    /**
     * 从 ToolInput 的 context 拿 ReactAgentState；若未注入，返回 null。
     * ToolBridgeAgentToolProvider 在 Task A.11 改写里把 state 放进 context。
     */
    private ReactAgentState extractState(ToolInput input) {
        if (input.context() == null) {
            return null;
        }
        Object raw = input.context().get(ToolContextKeys.CALLER_STATE);
        if (raw instanceof ReactAgentState rs) {
            return rs;
        }
        // 退化：没有 state，log 一下，search 会按无 state 处理
        log.debug("tools.search/describe/list 执行时未注入 CALLER_STATE，按 null state 处理");
        return null;
    }
}
