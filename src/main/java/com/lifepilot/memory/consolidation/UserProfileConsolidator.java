package com.lifepilot.memory.consolidation;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.scope.MemoryOriginType;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryRealityType;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 用户画像巩固器 — 定期将 L3 碎片实体聚合为连贯的用户画像文本。
 *
 * <p>从 L3 语义记忆中读取 PREFERENCE/HABIT/GOAL/SKILL 碎片实体，
 * 结合 L2 最近对话和 L4 偏好规则，调用 LLM 生成第三人称自然语言画像，
 * 存入 L3 作为 {@code __consolidated_profile} 特殊 CUSTOM 实体。</p>
 *
 * <p>所有依赖均可为 null，缺失时 {@link #consolidate()} 直接跳过。</p>
 *
 * @author zsg
 * @since 2026-04-15
 */
public class UserProfileConsolidator {

    private static final Logger log = LoggerFactory.getLogger(UserProfileConsolidator.class);

    /** 巩固画像实体名称。 */
    private static final String PROFILE_ENTITY_NAME = "__consolidated_profile";

    /** 提示词模板键。 */
    private static final String PROMPT_KEY = "generation/user-profile-consolidation";

    /** 用于收集偏好规则的类别列表。 */
    private static final List<String> PREFERENCE_CATEGORIES = List.of(
            "proactive-domain", "user-preference");

    /** LLM 调用超时。 */
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(60);

    /** 两次巩固之间的最小间隔 — 防止频繁调用 LLM。 */
    private static final Duration MIN_INTERVAL = Duration.ofHours(2);

    /** 上次巩固时间（防抖用）。 */
    private volatile Instant lastConsolidatedAt = Instant.EPOCH;

    @Nullable
    private final SemanticMemory semanticMemory;
    @Nullable
    private final EpisodicMemory episodicMemory;
    @Nullable
    private final ProceduralMemory proceduralMemory;
    @Nullable
    private final GenerationRouter generationRouter;
    @Nullable
    private final PromptRegistry promptRegistry;

    public UserProfileConsolidator(@Nullable SemanticMemory semanticMemory,
                                   @Nullable EpisodicMemory episodicMemory,
                                   @Nullable ProceduralMemory proceduralMemory,
                                   @Nullable GenerationRouter generationRouter,
                                   @Nullable PromptRegistry promptRegistry) {
        this.semanticMemory = semanticMemory;
        this.episodicMemory = episodicMemory;
        this.proceduralMemory = proceduralMemory;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 执行用户画像巩固。
     *
     * <p>核心依赖（SemanticMemory / GenerationRouter / PromptRegistry）缺失时直接跳过。
     * 任何异常静默捕获，不影响其他巩固步骤。</p>
     */
    public void consolidate() {
        if (semanticMemory == null || generationRouter == null || promptRegistry == null) {
            log.debug("用户画像巩固: 核心依赖缺失，已跳过");
            return;
        }
        // 防抖：距上次巩固不到 MIN_INTERVAL 则跳过
        if (Duration.between(lastConsolidatedAt, Instant.now()).compareTo(MIN_INTERVAL) < 0) {
            log.debug("用户画像巩固: 距上次不到{}小时，已跳过", MIN_INTERVAL.toHours());
            return;
        }

        try {
            doConsolidate();
            lastConsolidatedAt = Instant.now();
        } catch (Exception e) {
            log.warn("用户画像巩固: 执行失败, error={}", e.getMessage(), e);
        }
    }

    private void doConsolidate() {
        var filter = MemoryReadFilter.userProfile();

        // 1. 从 L3 读取碎片实体
        var preferences = semanticMemory.findCurrentByType(EntityType.PREFERENCE, filter);
        var habits = semanticMemory.findCurrentByType(EntityType.HABIT, filter);
        var goals = semanticMemory.findCurrentByType(EntityType.GOAL, filter);
        var skills = semanticMemory.findCurrentByType(EntityType.SKILL, filter);

        var allFragments = Stream.of(preferences, habits, goals, skills)
                .flatMap(List::stream)
                .toList();

        if (allFragments.isEmpty()) {
            log.debug("用户画像巩固: 无碎片实体，已跳过");
            return;
        }

        // 2. 从 L2 读取最近 7 天对话记录
        String recentConversations = buildRecentConversations();

        // 3. 从 L4 读取偏好规则
        String recentFeedback = buildRecentFeedback();

        // 4. 查找已有巩固画像
        var existingProfile = semanticMemory.findCurrentByNameAndType(
                PROFILE_ENTITY_NAME, EntityType.CUSTOM, filter);
        String currentPortrait = existingProfile
                .map(TemporalEntity::description)
                .orElse("无");

        // 5. 构建已知偏好与习惯（L3 碎片实体）
        String knownTraits = allFragments.stream()
                .map(e -> "- [" + e.type().label() + "] " + e.name() +
                        (e.description() != null && !e.description().isBlank()
                                ? ": " + e.description() : ""))
                .collect(Collectors.joining("\n"));
        if (knownTraits.isBlank()) {
            knownTraits = "无已知偏好";
        }

        // 6. 构建活跃目标列表
        String activeIntents = goals.stream()
                .map(g -> "- " + g.name() +
                        (g.description() != null ? ": " + g.description() : ""))
                .collect(Collectors.joining("\n"));
        if (activeIntents.isBlank()) {
            activeIntents = "无明确活跃目标";
        }

        // 7. 渲染 prompt 并调用 LLM
        String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                "currentPortrait", currentPortrait,
                "knownTraits", knownTraits,
                "recentConversations", recentConversations,
                "recentFeedback", recentFeedback,
                "activeIntents", activeIntents
        ));

        log.debug("用户画像巩固: 发起 LLM 调用, fragmentCount={}, promptChars={}",
                allFragments.size(), prompt.length());

        // skipCache=true：画像巩固每次输入不同但模板相似，语义缓存会错误命中旧结果
        LlmResponse response = generationRouter.call(
                LlmScene.BACKGROUND_ANALYSIS,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                LLM_TIMEOUT,
                true);

        String portraitText = response.content();
        if (portraitText == null || portraitText.isBlank()) {
            log.warn("用户画像巩固: LLM 返回空画像，已跳过写入");
            return;
        }

        // 7. 写入 L3
        var writeContext = new MemoryWriteContext(
                null,
                MemoryScope.USER_PROFILE,
                MemoryOriginType.CONSOLIDATION,
                MemoryRealityType.REAL,
                "user-profile-consolidation",
                null, null, null, null, null, null, null, null
        );

        // 派生来源 — 本次聚合画像使用的所有 L3 源实体 id，供
        // DerivedEntityListener 通过 derivation_sources LIKE '%<sourceId>%'
        // 反查到画像并触发 20% 阈值或级联 REGENERATION_NEEDED。
        List<String> derivationSources = allFragments.stream()
                .map(TemporalEntity::id)
                .toList();

        var now = Instant.now();
        if (existingProfile.isPresent()) {
            // UPDATE: 用 upsertWithConflictDetection 更新已有画像
            var existing = existingProfile.get();
            var updated = new TemporalEntity(
                    existing.id(),
                    EntityType.CUSTOM,
                    PROFILE_ENTITY_NAME,
                    portraitText,
                    existing.properties(),
                    existing.version(),
                    true,
                    existing.validFrom(),
                    null,
                    existing.sourceConversationId(),
                    1.0f,
                    existing.importanceScore(),
                    existing.accessCount(),
                    existing.lastAccessedAt(),
                    existing.createdAt(),
                    now,
                    // 画像为派生实体 — 继承旧生命周期字段，但强制 isDerived=true
                    // 并刷新 derivationSources 为本轮聚合使用的 L3 源实体 id 集。
                    existing.lifecycleState() == null ? LifecycleState.ACTIVE : existing.lifecycleState(),
                    existing.lifecycleReason(),
                    existing.expiresAt(),
                    existing.temporality() == null ? Temporality.PERSISTENT : existing.temporality(),
                    existing.succeededBy(),
                    true,
                    derivationSources
            );
            semanticMemory.upsertWithConflictDetection(updated, "user-profile-consolidation", writeContext);
            log.info("用户画像巩固: 已更新画像, entityId={}, chars={}, sources={}",
                    existing.id(), portraitText.length(), derivationSources.size());
        } else {
            // ADD: 创建新实体
            var newEntity = new TemporalEntity(
                    UUID.randomUUID().toString(),
                    EntityType.CUSTOM,
                    PROFILE_ENTITY_NAME,
                    portraitText,
                    Map.of(),
                    1,
                    true,
                    now,
                    null,
                    null,
                    1.0f,
                    0.9f,
                    0,
                    null,
                    now,
                    now,
                    // 画像为派生实体 — isDerived=true + derivationSources 指向 L3 源实体集。
                    LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT, null,
                    true,
                    derivationSources
            );
            semanticMemory.upsertWithConflictDetection(newEntity, "user-profile-consolidation", writeContext);
            log.info("用户画像巩固: 已创建画像, entityId={}, chars={}, sources={}",
                    newEntity.id(), portraitText.length(), derivationSources.size());
        }
    }

    /**
     * 从 L2 读取最近 7 天对话记录，拼接为摘要/标题列表。
     */
    private String buildRecentConversations() {
        if (episodicMemory == null) {
            return "无最近对话";
        }
        try {
            var conversations = episodicMemory.getRecent(Duration.ofDays(7));
            if (conversations.isEmpty()) {
                return "无最近对话";
            }
            return conversations.stream()
                    .map(c -> {
                        String label = c.summary() != null && !c.summary().isBlank()
                                ? c.summary()
                                : c.goal();
                        return "- " + label;
                    })
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.debug("用户画像巩固: 读取最近对话失败, error={}", e.getMessage());
            return "无最近对话";
        }
    }

    /**
     * 从 L4 读取偏好规则，拼接为摘要文本。
     */
    private String buildRecentFeedback() {
        if (proceduralMemory == null) {
            return "无偏好反馈";
        }
        try {
            var allRules = new ArrayList<PreferenceRule>();
            for (var category : PREFERENCE_CATEGORIES) {
                allRules.addAll(proceduralMemory.getPreferences(category));
            }
            if (allRules.isEmpty()) {
                return "无偏好反馈";
            }
            return allRules.stream()
                    .map(r -> "- " + r.key() +
                            (r.value() != null && !r.value().isBlank() ? ": " + r.value() : ""))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.debug("用户画像巩固: 读取偏好规则失败, error={}", e.getMessage());
            return "无偏好反馈";
        }
    }
}
