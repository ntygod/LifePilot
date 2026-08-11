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
 * 注册 {@code tool.search} Meta BuiltinTool。
 *
 * <p>工具发现面向所有未直接注入 Agent 的工具，包括 Java 原生工具和 MCP 外部工具。
 * Executor 从 ToolInput 的 context 拿当前 {@link ReactAgentState}，传给服务层。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class BuiltinToolSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolSearchProvider.class);

    private final ToolSearchService searchService;

    public BuiltinToolSearchProvider(
            ToolSearchService searchService) {
        this.searchService = searchService;
    }

    /** 构建 tool.search 内置工具定义。 */
    public BuiltinTool searchTool() {
        return BuiltinTool.builder()
                .id("tool.search")
                .name("搜索工具")
                .description("用关键词在工具能力目录中搜索未直接暴露的 Java/MCP 工具，返回可在下一轮调用的工具 ID 和输入 Schema。")
                .tags(List.of("搜索", "工具", "发现", "查找", "tools", "search"))
                .category(ToolCategory.INTROSPECTION)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "描述所需能力的关键词（中文或英文均可）"),
                                "category", Map.of("type", "string",
                                        "description", "可选 category 过滤：PERCEPTION/ACTION/COGNITION/STORAGE/INTERACTION/INTROSPECTION/EXTENSION"),
                                "limit", Map.of("type", "integer",
                                        "description", "最大返回数量，默认 5，上限 20")
                        ),
                        "required", List.of("query")
                )))
                .executor(this::executeSearch)
                .build();
    }

    private ToolResult executeSearch(ToolInput input) {
        String query = input.getOptionalParam("query", String.class).orElse("");
        String category = input.getOptionalParam("category", String.class).orElse(null);
        Integer limit = input.getOptionalParam("limit", Integer.class).orElse(null);

        ReactAgentState state = extractState(input);

        ToolSearchResult result = searchService.search(state, query, category, limit);
        List<Map<String, Object>> hits = result.results().stream()
                .map(hit -> Map.<String, Object>of(
                        "id", hit.id(),
                        "description", hit.description(),
                        "category", hit.category(),
                        "score", hit.score(),
                        "actions", hit.actions(),
                        "inputSchema", hit.inputSchema()
                ))
                .toList();
        return ToolResult.success(Map.of(
                "results", hits,
                "discovered_tool_ids", result.results().stream().map(ToolSearchHit::id).toList(),
                "total_matched", result.totalMatched(),
                "confidence", result.confidence().name(),
                "hint", result.hint() == null ? "" : result.hint()
        ));
    }

    /**
     * 从 ToolInput 的 context 拿 ReactAgentState；若未注入，返回 null。
     * ToolBridgeAgentToolProvider 把 state 放进 context。
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
        log.debug("tool.search 执行时未注入 CALLER_STATE，按 null state 处理");
        return null;
    }
}
