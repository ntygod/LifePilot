package com.lifepilot.agent.learning.consolidation;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.experience.ExperienceRecord;
import com.lifepilot.agent.learning.experience.ExperienceRecordContract;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 经验合并器 — 将语义相似的经验合并为泛化的元经验。
 *
 * <p>在 ConsolidationPipeline.consolidate() 中调用，语义巩固之后、经验提升之前执行。
 * 通过 VectorSearcher 检测两两余弦相似度 ≥ similarityThreshold 且 success 标志相同的经验对，
 * 由 LLM 提取共性模式并生成更抽象的元经验，原始经验归档。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class ExperienceMerger {

    private static final Logger log = LoggerFactory.getLogger(ExperienceMerger.class);
    private static final String PROMPT_KEY = "memory/experience-merge";

    private final SemanticMemory semanticMemory;
    private final VectorSearcher vectorSearcher;
    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final AgentLearningProperties.Experience.Merge config;

    public ExperienceMerger(SemanticMemory semanticMemory,
                            VectorSearcher vectorSearcher,
                            GenerationRouter generationRouter,
                            PromptRegistry promptRegistry,
                            AgentLearningProperties AgentLearningProperties) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "vectorSearcher 不能为空");
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter 不能为空");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry 不能为空");
        this.config = Objects.requireNonNull(AgentLearningProperties, "AgentLearningProperties 不能为空")
                .getExperience().getMerge();
    }

    /**
     * 执行经验合并。在 ConsolidationPipeline.consolidate() 中调用。
     *
     * <p>加载所有 EXPERIENCE 实体，检测两两相似度 ≥ threshold 且 success 相同的候选对，
     * 通过 LLM 合并为元经验，归档原始经验。</p>
     *
     * @return 合并统计
     */
    public MergeStats merge() {
        if (!config.isEnabled()) {
            log.debug("经验合并: 功能已关闭");
            return new MergeStats(0, 0, 0);
        }

        var allExperiences = requireExperienceList(semanticMemory.findCurrentByType(EntityType.EXPERIENCE));
        if (allExperiences.size() < 2) {
            log.debug("经验合并: 经验数量不足, count={}", allExperiences.size());
            return new MergeStats(0, 0, 0);
        }

        // 检测合并候选对
        var candidatePairs = findCandidatePairs(allExperiences);
        int candidatesFound = candidatePairs.size();
        log.info("经验合并: 发现候选对, count={}", candidatesFound);

        // 追踪已合并的实体 ID，避免同一轮中双重合并
        Set<String> mergedIds = new HashSet<>();
        int merged = 0;
        int skipped = 0;

        for (var pair : candidatePairs) {
            if (merged >= config.getMaxMergesPerRun()) {
                skipped += (candidatePairs.size() - merged - skipped);
                break;
            }

            // 跳过已被合并的实体
            if (mergedIds.contains(pair.entityA.id()) || mergedIds.contains(pair.entityB.id())) {
                skipped++;
                continue;
            }

            var mergedRecord = callLlmMerge(pair.entityA, pair.entityB);
            persistMergedExperience(mergedRecord, pair.entityA, pair.entityB);
            mergedIds.add(pair.entityA.id());
            mergedIds.add(pair.entityB.id());
            merged++;
        }

        log.info("经验合并: 完成, candidatesFound={}, merged={}, skipped={}",
                candidatesFound, merged, skipped);
        return new MergeStats(candidatesFound, merged, skipped);
    }

    /** 检测合并候选对：相似度 ≥ threshold 且 success 标志相同。 */
    private List<CandidatePair> findCandidatePairs(List<TemporalEntity> experiences) {
        float threshold = similarityThreshold(config.getSimilarityThreshold(), "经验合并相似度阈值");
        List<CandidatePair> pairs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (var entityA : experiences) {
            boolean successA = requiredSuccess(entityA);

            var searchResults = requireVectorResults(vectorSearcher.searchEntities(
                    entityA.textRepresentation(), 5, threshold));

            for (var result : searchResults) {
                // 排除自身
                if (result.entityId().equals(entityA.id())) continue;

                // 去重：确保每对只出现一次
                String pairKey = entityA.id().compareTo(result.entityId()) < 0
                        ? entityA.id() + "|" + result.entityId()
                        : result.entityId() + "|" + entityA.id();
                if (seen.contains(pairKey)) continue;

                // 在 experiences 列表中查找匹配实体
                var entityB = experiences.stream()
                        .filter(e -> e.id().equals(result.entityId()))
                        .findFirst()
                        .orElse(null);
                if (entityB == null) continue;

                // 检查 success 标志相同
                boolean successB = requiredSuccess(entityB);
                if (successA != successB) continue;

                seen.add(pairKey);
                pairs.add(new CandidatePair(entityA, entityB));
            }
        }
        return pairs;
    }

    /** 调用 LLM 合并两条经验。 */
    private ExperienceRecord callLlmMerge(TemporalEntity entityA, TemporalEntity entityB) {
        var vars = Map.<String, Object>of(
                "experienceA_scenario", entityA.name(),
                "experienceA_strategy", requireNonBlank(entityA.description(), "经验合并实体描述"),
                "experienceA_lessons", String.valueOf(requiredStringListProperty(entityA, "lessons")),
                "experienceA_tools", String.valueOf(requiredStringListProperty(entityA, "toolsUsed")),
                "experienceB_scenario", entityB.name(),
                "experienceB_strategy", requireNonBlank(entityB.description(), "经验合并实体描述"),
                "experienceB_lessons", String.valueOf(requiredStringListProperty(entityB, "lessons")),
                "experienceB_tools", String.valueOf(requiredStringListProperty(entityB, "toolsUsed"))
        );
        String prompt = promptRegistry.render(PROMPT_KEY, vars);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("经验合并 prompt 渲染结果不能为空");
        }
        Duration timeout = Duration.ofSeconds(positive(config.getLlmTimeoutSeconds(), "经验合并 LLM 超时秒数"));
        log.debug("经验合并: 发起 JSON 合并调用, timeoutSeconds={}, promptChars={}, entityA={}, entityB={}",
                timeout.toSeconds(), prompt.length(), entityA.id(), entityB.id());
        LlmResponse response = generationRouter.call(
                LlmScene.BACKGROUND_ANALYSIS,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                timeout);
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("经验合并 LLM 响应不能为空");
        }
        ExperienceRecordContract.validateLlmResponse(response.content(), "经验合并");
        var record = JsonOutputParser.parse(response.content(), ExperienceRecord.class);
        if (record == null || record.scenario() == null || record.scenario().isBlank()
                || record.strategy() == null || record.strategy().isBlank()) {
            throw new IllegalStateException("经验合并 LLM 响应缺少 scenario 或 strategy");
        }
        return record;
    }

    /** 持久化合并后的元经验，归档原始经验。 */
    private void persistMergedExperience(ExperienceRecord mergedRecord,
                                          TemporalEntity entityA,
                                          TemporalEntity entityB) {
        var now = Instant.now();

        // 截断 scenario 到 100 字符
        String name = mergedRecord.scenario();
        if (name != null && name.length() > 100) {
            name = name.substring(0, 100);
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scenario", mergedRecord.scenario());
        props.put("strategy", mergedRecord.strategy());
        props.put("lessons", mergedRecord.lessons());
        props.put("applicableConditions", mergedRecord.applicableConditions());
        props.put("toolsUsed", mergedRecord.toolsUsed());
        props.put("success", mergedRecord.success());
        props.put("effectivenessScore", mergedRecord.effectivenessScore());
        props.put("injectionCount", mergedRecord.injectionCount());
        props.put("positiveOutcomes", mergedRecord.positiveOutcomes());
        props.put("negativeOutcomes", mergedRecord.negativeOutcomes());
        props.put("mergedFrom", List.of(entityA.id(), entityB.id()));

        float importanceScore = Math.max(entityA.importanceScore(), entityB.importanceScore());
        float extractionConfidence = 0.8f;
        MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.LLM_SUMMARIZED_EXPERIENCE;
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, extractionConfidence);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);

        var mergedEntity = new TemporalEntity(
                UUID.randomUUID().toString(),
                EntityType.EXPERIENCE,
                name,
                mergedRecord.strategy(),
                props,
                1,
                true,
                now,
                null,
                null,
                extractionConfidence,
                importanceScore,
                0,
                null,
                now,
                now,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                evidenceKind,
                trustLevel,
                trustScore,
                2,
                null
        );

        // 写入合并后的元经验并归档原始经验 — 经验合并属冲突裁决，事件 source=CONFLICT_RESOLVE
        SqliteBusyRetry.run(() -> {
            semanticMemory.upsertWithConflictDetection(
                    mergedEntity,
                    "experience-merge",
                    MemoryWriteContext.consolidation("experience-merge"));
            semanticMemory.archive(entityA, ChangeSource.CONFLICT_RESOLVE);
            semanticMemory.archive(entityB, ChangeSource.CONFLICT_RESOLVE);
        });

        log.info("经验合并: 元经验已写入, mergedId={}, entityA={}, entityB={}",
                mergedEntity.id(), entityA.id(), entityB.id());
    }

    /** 合并候选对。 */
    private record CandidatePair(TemporalEntity entityA, TemporalEntity entityB) {}

    /** 合并统计。 */
    public record MergeStats(int candidatesFound, int merged, int skipped) {}

    private static List<TemporalEntity> requireExperienceList(List<TemporalEntity> experiences) {
        if (experiences == null) {
            throw new IllegalStateException("经验合并: EXPERIENCE 查询返回 null");
        }
        for (TemporalEntity experience : experiences) {
            if (experience == null) {
                throw new IllegalStateException("经验合并: EXPERIENCE 查询返回 null 实体");
            }
            requireCanonicalId(experience.id(), "经验合并实体 ID");
            if (experience.type() != EntityType.EXPERIENCE) {
                throw new IllegalStateException("经验合并: 查询结果包含非 EXPERIENCE 实体: " + experience.id());
            }
            requireNonBlank(experience.name(), "经验合并实体名称");
            requireNonBlank(experience.description(), "经验合并实体描述");
            requiredSuccess(experience);
            requiredStringListProperty(experience, "lessons");
            requiredStringListProperty(experience, "toolsUsed");
        }
        return experiences;
    }

    private static List<VectorSearchResult> requireVectorResults(List<VectorSearchResult> results) {
        if (results == null) {
            throw new IllegalStateException("经验合并: 向量搜索结果不能为空");
        }
        for (VectorSearchResult result : results) {
            if (result == null) {
                throw new IllegalStateException("经验合并: 向量搜索结果不能包含 null 元素");
            }
            requireCanonicalId(result.entityId(), "经验合并向量候选 ID");
            if (!Float.isFinite(result.similarity())
                    || result.similarity() < 0.0f
                    || result.similarity() > 1.0f) {
                throw new IllegalStateException("经验合并: 向量相似度必须在 [0,1] 范围内: "
                        + result.similarity());
            }
        }
        return results;
    }

    private static boolean requiredSuccess(TemporalEntity entity) {
        Object success = entity.properties().get("success");
        if (!(success instanceof Boolean value)) {
            throw new IllegalStateException("经验合并: EXPERIENCE success 必须是 boolean, entityId="
                    + entity.id());
        }
        return value;
    }

    private static List<?> requiredStringListProperty(TemporalEntity entity, String key) {
        Object value = entity.properties().get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("经验合并: EXPERIENCE " + key + " 必须是数组, entityId="
                    + entity.id());
        }
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalStateException("经验合并: EXPERIENCE " + key + " 只能包含非空字符串, entityId="
                        + entity.id());
            }
            if (!text.equals(text.trim())) {
                throw new IllegalStateException("经验合并: EXPERIENCE " + key + " 不能包含首尾空白, entityId="
                        + entity.id());
            }
        }
        return list;
    }

    private static float similarityThreshold(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + "必须在 [0,1] 范围内: " + value);
        }
        return value;
    }

    private static void requireCanonicalId(String value, String label) {
        requireNonBlank(value, label);
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + "不能包含首尾空白: " + value);
        }
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + "不能包含首尾空白: " + value);
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于 0: " + value);
        }
        return value;
    }
}
