package com.lifepilot.memory.experience;

import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.memory.governance.MemoryAccessPolicy;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.quality.MemoryQualityPolicy;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    @Nullable
    private final ProjectContextResolver projectContextResolver;
    @Nullable
    private final ChatSessionRepository chatSessionRepository;
    private final MemoryAccessPolicy memoryAccessPolicy;

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile Instant cacheRefreshedAt = Instant.EPOCH;

    public ToolTipResolver(@Nullable SemanticMemory semanticMemory) {
        this(semanticMemory, null, null, null);
    }

    public ToolTipResolver(@Nullable SemanticMemory semanticMemory,
                           @Nullable ProjectContextResolver projectContextResolver,
                           @Nullable ChatSessionRepository chatSessionRepository,
                           @Nullable MemoryAccessPolicy memoryAccessPolicy) {
        this.semanticMemory = semanticMemory;
        this.projectContextResolver = projectContextResolver;
        this.chatSessionRepository = chatSessionRepository;
        this.memoryAccessPolicy = memoryAccessPolicy != null ? memoryAccessPolicy : new MemoryAccessPolicy();
    }

    /**
     * 根据工具 ID 和当前会话查询经验提示。
     *
     * <p>会话可解析到项目上下文时，按项目读取范围检索工具级经验；缺少依赖或解析失败时
     * 回退主账户经验读取，保持提示链路 fail-soft。</p>
     */
    public String tipsFor(@Nullable String toolId, @Nullable String sessionId) {
        if (semanticMemory == null || toolId == null || toolId.isBlank()) {
            return "";
        }
        if (Duration.between(cacheRefreshedAt, Instant.now()).compareTo(CACHE_TTL) > 0) {
            cache.clear();
            cacheRefreshedAt = Instant.now();
        }
        ProjectContext projectContext = resolveProjectContext(sessionId);
        String cacheKey = toolId + "|" + contextCacheKey(projectContext);
        return cache.computeIfAbsent(cacheKey, ignored -> resolveFromMemory(toolId, projectContext));
    }

    private String resolveFromMemory(String toolId, @Nullable ProjectContext projectContext) {
        try {
            MemoryReadFilter filter = memoryAccessPolicy.buildProjectReadFilter(
                    projectContext, Set.of(MemoryScope.AGENT_EXPERIENCE));
            var experiences = semanticMemory.findCurrentByType(
                    EntityType.EXPERIENCE, filter);
            var tips = experiences.stream()
                    .filter(e -> SubtaskReflector.TOOL_LEVEL.equals(e.properties().get("granularity")))
                    .filter(e -> toolId.equals(e.properties().get("toolId")))
                    .filter(MemoryQualityPolicy::isPromptConsumable)
                    .filter(e -> e.trustScore() >= 0.55f)
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

    @Nullable
    private ProjectContext resolveProjectContext(@Nullable String sessionId) {
        if (projectContextResolver == null || chatSessionRepository == null
                || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return chatSessionRepository.findById(sessionId)
                    .map(session -> projectContextResolver.resolve(session.projectId()))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("工具经验提示解析项目上下文失败: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    private String contextCacheKey(@Nullable ProjectContext projectContext) {
        if (projectContext == null) {
            return "fallback";
        }
        return projectContext.projectId() != null ? projectContext.projectId() : "personal";
    }
}
