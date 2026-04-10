package com.lifepilot.memory.consolidation;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.experience.ExperienceRecord;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
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
    private final MemoryProperties.Experience.Merge config;

    public ExperienceMerger(SemanticMemory semanticMemory,
                            VectorSearcher vectorSearcher,
                            GenerationRouter generationRouter,
                            PromptRegistry promptRegistry,
                            MemoryProperties memoryProperties) {
        this.semanticMemory = semanticMemory;
        this.vectorSearcher = vectorSearcher;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.config = memoryProperties.getExperience().getMerge();
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

        var allExperiences = semanticMemory.findCurrentByType(EntityType.EXPERIENCE);
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

            try {
                var mergedRecord = callLlmMerge(pair.entityA, pair.entityB);
                if (mergedRecord == null) {
                    skipped++;
                    continue;
                }

                persistMergedExperience(mergedRecord, pair.entityA, pair.entityB);
                mergedIds.add(pair.entityA.id());
                mergedIds.add(pair.entityB.id());
                merged++;
            } catch (Exception e) {
                log.warn("经验合并: 合并失败, entityA={}, entityB={}, error={}",
                        pair.entityA.id(), pair.entityB.id(), e.getMessage());
                skipped++;
            }
        }

        log.info("经验合并: 完成, candidatesFound={}, merged={}, skipped={}",
                candidatesFound, merged, skipped);
        return new MergeStats(candidatesFound, merged, skipped);
    }

    /** 检测合并候选对：相似度 ≥ threshold 且 success 标志相同。 */
    private List<CandidatePair> findCandidatePairs(List<TemporalEntity> experiences) {
        List<CandidatePair> pairs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (var entityA : experiences) {
            boolean successA = Boolean.TRUE.equals(entityA.properties().get("success"));

            var searchResults = vectorSearcher.searchEntities(
                    entityA.textRepresentation(), 5, config.getSimilarityThreshold());

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
                boolean successB = Boolean.TRUE.equals(entityB.properties().get("success"));
                if (successA != successB) continue;

                seen.add(pairKey);
                pairs.add(new CandidatePair(entityA, entityB));
            }
        }
        return pairs;
    }

    /** 调用 LLM 合并两条经验。 */
    @jakarta.annotation.Nullable
    private ExperienceRecord callLlmMerge(TemporalEntity entityA, TemporalEntity entityB) {
        try {
            var vars = Map.<String, Object>of(
                    "experienceA_scenario", entityA.name(),
                    "experienceA_strategy", entityA.description() != null ? entityA.description() : "",
                    "experienceA_lessons", String.valueOf(entityA.properties().getOrDefault("lessons", List.of())),
                    "experienceA_tools", String.valueOf(entityA.properties().getOrDefault("toolsUsed", List.of())),
                    "experienceB_scenario", entityB.name(),
                    "experienceB_strategy", entityB.description() != null ? entityB.description() : "",
                    "experienceB_lessons", String.valueOf(entityB.properties().getOrDefault("lessons", List.of())),
                    "experienceB_tools", String.valueOf(entityB.properties().getOrDefault("toolsUsed", List.of()))
            );
            String prompt = promptRegistry.render(PROMPT_KEY, vars);
            Duration timeout = Duration.ofSeconds(Math.max(1, config.getLlmTimeoutSeconds()));
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
            return JsonOutputParser.parse(response.content(), ExperienceRecord.class);
        } catch (Exception e) {
            log.warn("经验合并: JSON 合并失败, entityA={}, entityB={}, errorType={}, error={}",
                    entityA.id(), entityB.id(), e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
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
        props.put("lessons", mergedRecord.lessons() != null ? mergedRecord.lessons() : List.of());
        props.put("applicableConditions", mergedRecord.applicableConditions() != null
                ? mergedRecord.applicableConditions() : List.of());
        props.put("toolsUsed", mergedRecord.toolsUsed() != null ? mergedRecord.toolsUsed() : List.of());
        props.put("success", mergedRecord.success());
        props.put("effectivenessScore", mergedRecord.effectivenessScore());
        props.put("injectionCount", mergedRecord.injectionCount());
        props.put("positiveOutcomes", mergedRecord.positiveOutcomes());
        props.put("negativeOutcomes", mergedRecord.negativeOutcomes());
        props.put("mergedFrom", List.of(entityA.id(), entityB.id()));

        float importanceScore = Math.max(entityA.importanceScore(), entityB.importanceScore());

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
                0.8f,
                importanceScore,
                0,
                null,
                now,
                now
        );

        // 写入合并后的元经验
        semanticMemory.upsertWithConflictDetection(mergedEntity, "experience-merge");
        vectorSearcher.upsertEntityVector(mergedEntity.id(), mergedEntity.textRepresentation());

        // 归档原始经验
        semanticMemory.archive(entityA);
        semanticMemory.archive(entityB);

        log.info("经验合并: 元经验已写入, mergedId={}, entityA={}, entityB={}",
                mergedEntity.id(), entityA.id(), entityB.id());
    }

    /** 合并候选对。 */
    private record CandidatePair(TemporalEntity entityA, TemporalEntity entityB) {}

    /** 合并统计。 */
    public record MergeStats(int candidatesFound, int merged, int skipped) {}
}
