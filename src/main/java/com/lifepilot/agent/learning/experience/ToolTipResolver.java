package com.lifepilot.agent.learning.experience;

import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** 缓存 TTL。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /** 单个工具最多取几条提示。 */
    private static final int MAX_TIPS_PER_TOOL = 2;

    private final SemanticMemory semanticMemory;
    private final ProjectContextResolver projectContextResolver;
    private final ChatSessionRepository chatSessionRepository;
    private final MemoryAccessPolicy memoryAccessPolicy;

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile Instant cacheRefreshedAt = Instant.EPOCH;

    public ToolTipResolver(SemanticMemory semanticMemory,
                           ProjectContextResolver projectContextResolver,
                           ChatSessionRepository chatSessionRepository,
                           MemoryAccessPolicy memoryAccessPolicy) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory");
        this.projectContextResolver = Objects.requireNonNull(projectContextResolver, "projectContextResolver");
        this.chatSessionRepository = Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
        this.memoryAccessPolicy = Objects.requireNonNull(memoryAccessPolicy, "memoryAccessPolicy");
    }

    /**
     * 根据工具 ID 和当前会话查询经验提示。
     *
     * <p>会话可解析到项目上下文时，按项目读取范围检索工具级经验；没有会话时读取主账户经验。</p>
     */
    public String tipsFor(@Nullable String toolId, @Nullable String sessionId) {
        if (toolId == null || toolId.isBlank()) {
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

    private String resolveFromMemory(String toolId, ProjectContext projectContext) {
        MemoryReadFilter filter = memoryAccessPolicy.buildProjectReadFilter(
                projectContext, Set.of(MemoryScope.AGENT_EXPERIENCE));
        var experiences = Objects.requireNonNull(
                semanticMemory.findCurrentByType(EntityType.EXPERIENCE, filter),
                "工具经验查询结果不能为空");
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
    }

    private ProjectContext resolveProjectContext(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Objects.requireNonNull(
                    projectContextResolver.resolve(null),
                    "主账户项目上下文不能为空");
        }
        java.util.Optional<com.lifepilot.interaction.web.model.ChatSession> session =
                Objects.requireNonNull(chatSessionRepository.findById(sessionId), "会话查询结果不能为空");
        if (session.isEmpty()) {
            return Objects.requireNonNull(
                    projectContextResolver.resolve(null),
                    "主账户项目上下文不能为空");
        }
        String projectId = session.get().projectId();
        return Objects.requireNonNull(
                projectContextResolver.resolve(projectId),
                "项目上下文不能为空");
    }

    private String contextCacheKey(ProjectContext projectContext) {
        return projectContext.projectId() != null ? projectContext.projectId() : "personal";
    }
}
