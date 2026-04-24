package com.lifepilot.memory.experience;

import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具级经验提示查询器。
 *
 * <p>从 L3 EXPERIENCE 中检索 {@code granularity=TOOL_LEVEL} 且 {@code toolId} 匹配的经验，
 * 按 importanceScore 降序取 top 2，格式化为简短提示。结果按工具 ID 缓存 30 分钟。</p>
 *
 * <p>作为独立组件暴露给呈现层（{@code ProviderMessageBuilder}）。工具执行层不再往
 * {@code Observation.output} 混入装饰文本，装饰动态拼接只在构造 LLM 消息时进行，
 * 确保 {@code Observation.output} 始终是纯 JSON。</p>
 *
 * @author zsg
 * @since 2026-04-17
 */
public class ToolTipResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolTipResolver.class);

    /** 缓存 TTL。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /** 单个工具最多取几条提示。 */
    private static final int MAX_TIPS_PER_TOOL = 2;

    @Nullable
    private final SemanticMemory semanticMemory;

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile Instant cacheRefreshedAt = Instant.EPOCH;

    public ToolTipResolver(@Nullable SemanticMemory semanticMemory) {
        this.semanticMemory = semanticMemory;
    }

    /**
     * 根据工具 ID 查询经验提示。
     *
     * @param toolId 工具标识
     * @return 提示文本（无提示或 SemanticMemory 未配置时返回空串）
     */
    public String tipsFor(@Nullable String toolId) {
        if (semanticMemory == null || toolId == null || toolId.isBlank()) {
            return "";
        }
        if (Duration.between(cacheRefreshedAt, Instant.now()).compareTo(CACHE_TTL) > 0) {
            cache.clear();
            cacheRefreshedAt = Instant.now();
        }
        return cache.computeIfAbsent(toolId, this::resolveFromMemory);
    }

    private String resolveFromMemory(String toolId) {
        try {
            // TODO(plan-1-后续): 接入 ProjectContext，按当前项目构造 filter；
            // Plan 1 先按主账户维度读取工具经验 tip（缓存维度也需同步调整为按 projectId 分桶）。
            var experiences = semanticMemory.findCurrentByType(
                    EntityType.EXPERIENCE, MemoryReadFilter.agentExperience());
            var tips = experiences.stream()
                    .filter(e -> SubtaskReflector.TOOL_LEVEL.equals(e.properties().get("granularity")))
                    .filter(e -> toolId.equals(e.properties().get("toolId")))
                    .sorted(Comparator.comparingDouble(TemporalEntity::importanceScore).reversed())
                    .limit(MAX_TIPS_PER_TOOL)
                    .toList();
            if (tips.isEmpty()) {
                return "";
            }
            var sb = new StringBuilder("[历史经验提示] ");
            for (var tip : tips) {
                var lessons = tip.properties().get("lessons");
                if (lessons instanceof List<?> lessonList && !lessonList.isEmpty()) {
                    sb.append(lessonList.getFirst()).append("。");
                } else if (tip.description() != null) {
                    sb.append(tip.description()).append("。");
                }
            }
            return sb.toString().strip();
        } catch (Exception e) {
            log.debug("工具经验提示加载失败: toolId={}, error={}", toolId, e.getMessage());
            return "";
        }
    }
}
