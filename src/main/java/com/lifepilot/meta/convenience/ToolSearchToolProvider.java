package com.lifepilot.meta.convenience;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.search.ToolSearchIndex;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * 工具搜索元工具提供者 — 构建并注册 meta.search_tools 工具。
 *
 * <p>实现延迟工具加载的核心机制：LLM 通过调用此工具发现当前不可见的工具，
 * 发现结果由 ReactAgentLoop 拾取并扩展可用工具集。</p>
 *
 * @author zsg
 * @since 2026-04-09
 */
public class ToolSearchToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchToolProvider.class);
    private static final String TOOL_ID = "meta.search_tools";
    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final DynamicToolRegistry toolRegistry;
    private final MetaProperties.DeferredToolLoading config;
    private final ToolSearchIndex searchIndex;
    @Nullable
    private final SkillRegistry skillRegistry;

    public ToolSearchToolProvider(DynamicToolRegistry toolRegistry,
                                   MetaProperties.DeferredToolLoading config,
                                   @Nullable EmbeddingRouter embeddingRouter,
                                   @Nullable SkillRegistry skillRegistry) {
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.searchIndex = new ToolSearchIndex(embeddingRouter);
        this.skillRegistry = skillRegistry;
    }

    /**
     * 注册 meta.search_tools 工具到 DynamicToolRegistry。
     *
     * @param registry 动态工具注册中心
     */
    public void registerTool(DynamicToolRegistry registry) {
        registry.registerBuiltinTool(buildSearchTool());
        log.info("工具搜索元工具注册完成: id={}", TOOL_ID);
    }

    /** 使搜索索引失效，工具注册表变更时调用。 */
    public void invalidateIndex() {
        searchIndex.invalidate();
    }

    private BuiltinTool buildSearchTool() {
        return BuiltinTool.builder()
                .id(TOOL_ID)
                .name("搜索可用工具")
                .description("""
                    Search for available tools by describing what you need. \
                    Use this when you need a capability not in your current tool set. \
                    Discovered tools become available immediately in subsequent turns. \
                    Examples: "edit file content", "run git commands", "browser automation", \
                    "execute code", "manage cron tasks", "send notifications"\
                    """)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "Natural language description of the tool capability needed"
                                )
                        ),
                        "required", List.of("query")
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .category(ToolCategory.INTROSPECTION)
                .tags(INFRA_TAGS)
                .executor(this::executeSearch)
                .build();
    }

    private ToolResult executeSearch(ToolInput input) {
        String query = input.getParam("query", String.class);

        // 懒构建索引
        if (!searchIndex.isBuilt()) {
            searchIndex.buildIndex(toolRegistry.getToolSnapshot());
        }

        // 排除自身和始终加载的工具
        Set<String> excludeIds = new HashSet<>(config.getAlwaysLoadedToolIds());
        excludeIds.add(TOOL_ID);

        var results = searchIndex.search(
                query,
                config.getMaxSearchResults(),
                config.getMinScoreThreshold(),
                excludeIds
        );

        var toolList = results.stream()
                .map(r -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("tool_id", r.toolId());
                    m.put("name", r.name());
                    m.put("description", r.description());
                    m.put("category", r.category());
                    return Map.<String, Object>copyOf(m);
                })
                .toList();

        // 同时搜索关联 Skill — 避免 LLM 只发现工具而错过使用指导
        var relatedSkills = searchRelatedSkills(query);

        var data = new LinkedHashMap<String, Object>();
        data.put("found", toolList.size());
        data.put("tools", toolList);
        if (!relatedSkills.isEmpty()) {
            data.put("related_skills", relatedSkills);
        }
        if (toolList.isEmpty() && relatedSkills.isEmpty()) {
            data.put("hint", "No matching tools found. Try a different query or rephrase.");
        } else if (!relatedSkills.isEmpty()) {
            data.put("hint", "Tools are now available. Related skills provide detailed usage guidance — "
                    + "call load_skill with the skill IDs for best results.");
        } else {
            data.put("hint", "These tools are now available. Call them directly in your next response.");
        }

        log.debug("工具搜索完成: query={}, tools={}, skills={}", query, toolList.size(), relatedSkills.size());
        return ToolResult.success(Map.copyOf(data));
    }

    /**
     * 搜索与查询相关的 Skill — 确保 LLM 发现工具时同时获知使用指导。
     *
     * <p>利用 SkillRegistry 的语义搜索能力，返回最多 3 个匹配 Skill 的摘要信息。
     * 结果中包含 skill_id，LLM 可据此调用 load_skill 获取完整指令。</p>
     */
    private List<Map<String, Object>> searchRelatedSkills(String query) {
        if (skillRegistry == null) {
            return List.of();
        }
        try {
            return skillRegistry.search(query, 3).stream()
                    .map(skill -> {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("skill_id", skill.id());
                        m.put("name", skill.name());
                        m.put("description", skill.description());
                        return Map.<String, Object>copyOf(m);
                    })
                    .toList();
        } catch (Exception e) {
            log.debug("Skill 搜索失败: {}", e.getMessage());
            return List.of();
        }
    }
}
