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
import com.lifepilot.project.context.ProjectContextResolution;
import com.lifepilot.project.context.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ToolTipResolver.class);

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
     * <p>会话可解析到项目上下文时，按项目读取范围检索工具级经验；没有会话时读取主账户经验。
     * 项目上下文解析失败时跳过提示，避免跨项目经验泄漏。</p>
     */
    public String tipsFor(@Nullable String toolId, @Nullable String sessionId) {
        if (toolId == null || toolId.isBlank()) {
            return "";
        }
        if (Duration.between(cacheRefreshedAt, Instant.now()).compareTo(CACHE_TTL) > 0) {
            cache.clear();
            cacheRefreshedAt = Instant.now();
        }
        ProjectContextResolution resolution = resolveProjectContext(sessionId);
        if (resolution.failed()) {
            return "";
        }
        ProjectContext projectContext = resolution.context();
        String cacheKey = toolId + "|" + contextCacheKey(projectContext);
        return cache.computeIfAbsent(cacheKey, ignored -> resolveFromMemory(toolId, projectContext));
    }

    private String resolveFromMemory(String toolId, ProjectContext projectContext) {
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

    private ProjectContextResolution resolveProjectContext(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            try {
                return ProjectContextResolution.resolved(projectContextResolver.resolve(null));
            } catch (Exception e) {
                log.debug("工具经验提示解析主账户上下文失败: error={}", e.getMessage());
                return ProjectContextResolution.failed("personal_context_resolution_failed");
            }
        }
        java.util.Optional<com.lifepilot.interaction.web.model.ChatSession> session;
        try {
            session = chatSessionRepository.findById(sessionId);
        } catch (Exception e) {
            log.debug("工具经验提示查询会话项目归属失败: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return ProjectContextResolution.failed("chat_session_lookup_failed");
        }
        if (session.isEmpty()) {
            try {
                return ProjectContextResolution.resolved(projectContextResolver.resolve(null));
            } catch (Exception e) {
                log.debug("工具经验提示解析主账户上下文失败: sessionId={}, error={}",
                        sessionId, e.getMessage());
                return ProjectContextResolution.failed("personal_context_resolution_failed");
            }
        }
        String projectId = session.get().projectId();
        try {
            return ProjectContextResolution.resolved(projectContextResolver.resolve(projectId));
        } catch (Exception e) {
            log.debug("工具经验提示解析项目上下文失败: sessionId={}, projectId={}, error={}",
                    sessionId, projectId != null ? projectId : "<personal>", e.getMessage());
            return ProjectContextResolution.failed("project_context_resolution_failed");
        }
    }

    private String contextCacheKey(ProjectContext projectContext) {
        return projectContext.projectId() != null ? projectContext.projectId() : "personal";
    }
}
