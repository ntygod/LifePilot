package com.lifepilot.agent.learning.consolidation;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.store.procedural.PreferenceRule;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** 画像源签名属性键：用于判断 L3/L4/L2 输入是否真的发生变化。 */
    static final String PROFILE_SOURCE_SIGNATURE_KEY = "profileSourceSignature";

    /** 画像源签名版本：签名字段调整时递增，避免误用旧算法结果。 */
    private static final String PROFILE_SOURCE_SIGNATURE_VERSION = "v1";

    /** 画像源数量属性键，便于 UI/调试观察画像由多少条碎片派生。 */
    private static final String PROFILE_SOURCE_COUNT_KEY = "profileSourceCount";

    /** 最近一次画像巩固时间属性键。 */
    private static final String PROFILE_CONSOLIDATED_AT_KEY = "profileConsolidatedAt";

    /** LLM 调用超时。 */
    private final Duration llmTimeout;

    private final SemanticMemory semanticMemory;
    private final EpisodicMemory episodicMemory;
    private final ProceduralMemory proceduralMemory;
    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;

    public UserProfileConsolidator(SemanticMemory semanticMemory,
                                   EpisodicMemory episodicMemory,
                                   ProceduralMemory proceduralMemory,
                                   GenerationRouter generationRouter,
                                   PromptRegistry promptRegistry,
                                   Duration llmTimeout) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory");
        this.episodicMemory = Objects.requireNonNull(episodicMemory, "episodicMemory");
        this.proceduralMemory = Objects.requireNonNull(proceduralMemory, "proceduralMemory");
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry");
        this.llmTimeout = Objects.requireNonNull(llmTimeout, "llmTimeout");
    }

    /** 执行用户画像巩固。 */
    public void consolidate() {
        consolidate(false);
    }

    /**
     * 执行用户画像巩固。
     *
     * @param force 是否绕过最小间隔防抖；手动触发使用 true，定时/空闲触发使用 false
     */
    public void consolidate(boolean force) {
        doConsolidate(force);
    }

    private void doConsolidate(boolean force) {
        // Plan 1 设计：后台巩固任务语义为"主账户画像"—— 项目空间不参与巩固，
        // 项目实体维持在各自 space 内，避免跨项目画像串味。后续若需要项目级巩固
        // 应新开一个 per-project consolidator，而非在此处接 ProjectContext。
        var filter = MemoryReadFilter.userProfile();

        // 1. 从 L3 读取碎片实体
        var preferences = semanticMemory.findCurrentByType(EntityType.PREFERENCE, filter);
        var habits = semanticMemory.findCurrentByType(EntityType.HABIT, filter);
        var goals = semanticMemory.findCurrentByType(EntityType.GOAL, filter);
        var skills = semanticMemory.findCurrentByType(EntityType.SKILL, filter);

        var allFragments = Stream.of(preferences, habits, goals, skills)
                .flatMap(List::stream)
                .filter(MemoryQualityPolicy::isPromptConsumable)
                .sorted(Comparator.comparing(TemporalEntity::id))
                .toList();

        if (allFragments.isEmpty()) {
            log.debug("用户画像巩固: 无碎片实体，已跳过");
            return;
        }

        // 2. 从 L2 读取最近 7 天对话记录
        String recentConversations = buildRecentConversations();

        // 3. 从 L4 读取偏好规则
        Set<String> fragmentIds = allFragments.stream()
                .map(TemporalEntity::id)
                .collect(Collectors.toSet());
        String recentFeedback = buildRecentFeedback(fragmentIds);

        // 4. 查找已有巩固画像
        var existingProfile = semanticMemory.findCurrentByNameAndType(
                PROFILE_ENTITY_NAME, EntityType.CUSTOM, filter);
        String sourceSignature = buildSourceSignature(allFragments, recentConversations, recentFeedback);
        if (isSourceUnchanged(existingProfile, sourceSignature)) {
            log.debug("用户画像巩固: 源签名未变化，跳过 LLM, sourceCount={}", allFragments.size());
            return;
        }
        // 源签名本身已是天然去重：碎片实体没变 → 签名不变 → 上面已 return。
        // 走到这里说明源确实变了，直接重算，不再额外防抖。
        String currentPortrait = existingProfile
                .filter(MemoryQualityPolicy::isPromptConsumable)
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
        String activeIntents = allFragments.stream()
                .filter(e -> e.type() == EntityType.GOAL)
                .sorted(Comparator.comparing(TemporalEntity::id))
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
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("用户画像巩固 Prompt 渲染为空");
        }

        log.debug("用户画像巩固: 发起 LLM 调用, fragmentCount={}, promptChars={}",
                allFragments.size(), prompt.length());

        // skipCache=true：画像巩固每次输入不同但模板相似，语义缓存会错误命中旧结果
        LlmResponse response = Objects.requireNonNull(generationRouter.call(
                LlmScene.BACKGROUND_ANALYSIS,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                llmTimeout,
                true), "用户画像巩固 LLM 响应不能为空");

        String portraitText = response.content();
        if (portraitText == null || portraitText.isBlank()) {
            throw new IllegalStateException("用户画像巩固 LLM 返回空画像");
        }

        // 7. 写入 L3
        var writeContext = new MemoryWriteContext(
                null,
                MemoryScope.USER_PROFILE,
                MemoryOriginType.CONSOLIDATION,
                MemoryRealityType.REAL,
                "user-profile-consolidation",
                null, null, null, null, null, null
        );

        // 派生来源 — 本次聚合画像使用的所有 L3 源实体 id，供
        // DerivedEntityListener 通过 derivation_sources LIKE '%<sourceId>%'
        // 反查到画像并触发 20% 阈值或级联 REGENERATION_NEEDED。
        List<String> derivationSources = allFragments.stream()
                .map(TemporalEntity::id)
                .toList();

        var now = Instant.now();
        MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.DERIVED;
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, 1.0f);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        int evidenceCount = derivationSources.size();
        if (existingProfile.isPresent()) {
            // UPDATE: 用 upsertWithConflictDetection 更新已有画像
            var existing = existingProfile.get();
            Map<String, Object> updatedProperties = withProfileMetadata(
                    existing.properties(), sourceSignature, allFragments.size(), now);
            var updated = new TemporalEntity(
                    existing.id(),
                    EntityType.CUSTOM,
                    PROFILE_ENTITY_NAME,
                    portraitText,
                    updatedProperties,
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
                    // 画像为派生实体 — 重算成功后必须回到 ACTIVE，避免
                    // REGENERATION_NEEDED 继续污染消费侧。
                    LifecycleState.ACTIVE,
                    null,
                    null,
                    Temporality.PERSISTENT,
                    null,
                    true,
                    derivationSources,
                    evidenceKind,
                    trustLevel,
                    trustScore,
                    evidenceCount,
                    null
            );
            semanticMemory.upsertWithConflictDetection(updated, "user-profile-consolidation", writeContext);
            log.info("用户画像巩固: 已更新画像, entityId={}, chars={}, sources={}",
                    existing.id(), portraitText.length(), derivationSources.size());
        } else {
            // ADD: 创建新实体
            Map<String, Object> profileProperties = withProfileMetadata(
                    Map.of(), sourceSignature, allFragments.size(), now);
            var newEntity = new TemporalEntity(
                    UUID.randomUUID().toString(),
                    EntityType.CUSTOM,
                    PROFILE_ENTITY_NAME,
                    portraitText,
                    profileProperties,
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
                    derivationSources,
                    evidenceKind,
                    trustLevel,
                    trustScore,
                    evidenceCount,
                    null
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
    }

    /**
     * 从 L4 读取偏好规则，拼接为摘要文本。
     */
    private String buildRecentFeedback(Set<String> consumableFragmentIds) {
        var allRules = new ArrayList<PreferenceRule>();
        for (var category : PREFERENCE_CATEGORIES) {
            allRules.addAll(proceduralMemory.getPreferences(category));
        }
        allRules.removeIf(rule -> rule.sourceEntityId() == null
                || rule.sourceEntityId().isBlank()
                || !consumableFragmentIds.contains(rule.sourceEntityId()));
        if (allRules.isEmpty()) {
            return "无偏好反馈";
        }
        return allRules.stream()
                .sorted(Comparator
                        .comparing(PreferenceRule::category)
                        .thenComparing(PreferenceRule::key)
                        .thenComparing(r -> r.sourceEntityId() != null ? r.sourceEntityId() : ""))
                .map(r -> "- " + r.key() +
                        (r.value() != null && !r.value().isBlank() ? ": " + r.value() : ""))
                .collect(Collectors.joining("\n"));
    }

    private boolean isSourceUnchanged(java.util.Optional<TemporalEntity> existingProfile,
                                      String sourceSignature) {
        return existingProfile
                .filter(MemoryQualityPolicy::isPromptConsumable)
                .map(TemporalEntity::properties)
                .map(props -> props.get(PROFILE_SOURCE_SIGNATURE_KEY))
                .map(String::valueOf)
                .filter(sourceSignature::equals)
                .isPresent();
    }

    private Map<String, Object> withProfileMetadata(Map<String, Object> properties,
                                                    String sourceSignature,
                                                    int sourceCount,
                                                    Instant consolidatedAt) {
        var updated = new LinkedHashMap<String, Object>(properties);
        updated.put(PROFILE_SOURCE_SIGNATURE_KEY, sourceSignature);
        updated.put(PROFILE_SOURCE_COUNT_KEY, sourceCount);
        updated.put(PROFILE_CONSOLIDATED_AT_KEY, consolidatedAt.toString());
        return updated;
    }

    private String buildSourceSignature(List<TemporalEntity> fragments,
                                        String recentConversations,
                                        String recentFeedback) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateSignaturePart(digest, PROFILE_SOURCE_SIGNATURE_VERSION);
            for (TemporalEntity fragment : fragments) {
                updateSignaturePart(digest, "fragment");
                updateSignaturePart(digest, fragment.id());
                updateSignaturePart(digest, fragment.type().name());
                updateSignaturePart(digest, fragment.name());
                updateSignaturePart(digest, fragment.description());
                updateSignaturePart(digest, String.valueOf(fragment.version()));
                updateSignaturePart(digest, fragment.lifecycleState().name());
                updateSignaturePart(digest, fragment.evidenceKind().name());
                updateSignaturePart(digest, fragment.trustLevel().name());
                updateSignaturePart(digest, String.format(java.util.Locale.ROOT, "%.3f", fragment.trustScore()));
            }
            updateSignaturePart(digest, "recentConversations");
            updateSignaturePart(digest, recentConversations);
            updateSignaturePart(digest, "recentFeedback");
            updateSignaturePart(digest, recentFeedback);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }

    private void updateSignaturePart(MessageDigest digest, @Nullable String value) {
        String safe = value != null ? value : "<null>";
        digest.update(Integer.toString(safe.length()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(safe.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }
}
