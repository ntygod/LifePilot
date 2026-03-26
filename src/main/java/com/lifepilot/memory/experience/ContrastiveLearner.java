package com.lifepilot.memory.experience;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.config.MemoryProperties;
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
 * 对比学习器 — 比较相似场景下的成功/失败轨迹对，提取对比洞察。
 *
 * <p>在 asyncPostProcess 的 Virtual Thread 中调用，ExperienceSummarizer 完成后执行。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class ContrastiveLearner {

    private static final Logger log = LoggerFactory.getLogger(ContrastiveLearner.class);
    private static final String PROMPT_KEY = "memory/contrastive-learning";

    private final SemanticMemory semanticMemory;
    private final VectorSearcher vectorSearcher;
    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final MemoryProperties.Experience.Contrastive config;

    public ContrastiveLearner(SemanticMemory semanticMemory,
                               VectorSearcher vectorSearcher,
                               GenerationRouter generationRouter,
                               PromptRegistry promptRegistry,
                               MemoryProperties memoryProperties) {
        this.semanticMemory = semanticMemory;
        this.vectorSearcher = vectorSearcher;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.config = memoryProperties.getExperience().getContrastive();
    }

    /**
     * 在经验提炼完成后执行对比学习。
     *
     * @param newExperience 刚提炼的经验实体
     */
    public void learn(TemporalEntity newExperience) {
        if (!config.isEnabled()) {
            log.debug("对比学习: 功能已关闭");
            return;
        }

        try {
            // 获取新经验的 success 标志
            boolean newSuccess = Boolean.TRUE.equals(newExperience.properties().get("success"));

            // 搜索相似经验
            var searchResults = vectorSearcher.searchEntities(
                    newExperience.textRepresentation(), 5, config.getSimilarityThreshold());

            // 过滤 success 标志相反的经验
            TemporalEntity matchedEntity = null;
            for (var result : searchResults) {
                // 排除自身
                if (result.entityId().equals(newExperience.id())) continue;

                var optEntity = semanticMemory.findById(result.entityId());
                if (optEntity.isEmpty()) continue;

                var entity = optEntity.get();
                // 必须是 EXPERIENCE 类型
                if (entity.type() != EntityType.EXPERIENCE) continue;

                boolean entitySuccess = Boolean.TRUE.equals(entity.properties().get("success"));
                if (entitySuccess != newSuccess) {
                    matchedEntity = entity;
                    break;
                }
            }

            if (matchedEntity == null) {
                log.debug("对比学习: 未找到匹配的对比轨迹对, entityId={}", newExperience.id());
                return;
            }

            // 确定成功/失败经验
            TemporalEntity successExp = newSuccess ? newExperience : matchedEntity;
            TemporalEntity failureExp = newSuccess ? matchedEntity : newExperience;

            // LLM 对比分析
            var insight = analyzeContrast(successExp, failureExp);
            if (insight == null) return;

            // 空洞察丢弃
            if (insight.failureReason() == null || insight.failureReason().isBlank()
                    || insight.successFactor() == null || insight.successFactor().isBlank()) {
                log.debug("对比学习: 空洞察丢弃, successId={}, failureId={}",
                        successExp.id(), failureExp.id());
                return;
            }

            // 写入对比经验实体
            persistContrastiveExperience(insight, successExp, failureExp);

        } catch (Exception e) {
            log.warn("对比学习失败: entityId={}, error={}", newExperience.id(), e.getMessage());
        }
    }

    /** 调用 LLM 进行对比分析。 */
    @jakarta.annotation.Nullable
    private ContrastiveInsight analyzeContrast(TemporalEntity successExp, TemporalEntity failureExp) {
        try {
            var vars = Map.<String, Object>of(
                    "successScenario", successExp.name(),
                    "successStrategy", successExp.description() != null ? successExp.description() : "",
                    "successLessons", String.valueOf(successExp.properties().getOrDefault("lessons", List.of())),
                    "successTools", String.valueOf(successExp.properties().getOrDefault("toolsUsed", List.of())),
                    "failureScenario", failureExp.name(),
                    "failureStrategy", failureExp.description() != null ? failureExp.description() : "",
                    "failureLessons", String.valueOf(failureExp.properties().getOrDefault("lessons", List.of())),
                    "failureTools", String.valueOf(failureExp.properties().getOrDefault("toolsUsed", List.of()))
            );
            String prompt = promptRegistry.render(PROMPT_KEY, vars);
            Duration timeout = Duration.ofSeconds(Math.max(1, config.getLlmTimeoutSeconds()));
            log.debug("对比学习: 发起 JSON 分析调用, timeoutSeconds={}, promptChars={}, successId={}, failureId={}",
                    timeout.toSeconds(), prompt.length(), successExp.id(), failureExp.id());
            LlmResponse response = generationRouter.call(
                    "contrastive-learning",
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    timeout);
            return JsonOutputParser.parse(response.content(), ContrastiveInsight.class);
        } catch (Exception e) {
            log.warn("对比学习: JSON 分析失败, errorType={}, error={}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** 持久化对比经验实体。 */
    private void persistContrastiveExperience(ContrastiveInsight insight,
                                               TemporalEntity successExp,
                                               TemporalEntity failureExp) {
        var now = Instant.now();
        String name = "对比洞察: " + successExp.name();
        if (name.length() > 100) name = name.substring(0, 100);

        String description = insight.successFactor() + " vs " + insight.failureReason();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("contrastive", true);
        props.put("successEntityId", successExp.id());
        props.put("failureEntityId", failureExp.id());
        props.put("failureReason", insight.failureReason());
        props.put("successFactor", insight.successFactor());
        props.put("contrastiveLessons", insight.contrastiveLessons() != null
                ? insight.contrastiveLessons() : List.of());
        props.put("avoidanceStrategy", insight.avoidanceStrategy());
        props.put("success", true); // 对比洞察视为正面经验

        var entity = new TemporalEntity(
                UUID.randomUUID().toString(),
                EntityType.EXPERIENCE,
                name,
                description,
                props,
                1,
                true,
                now,
                null,
                null,
                0.8f,
                config.getInitialImportance(),
                0,
                null,
                now,
                now
        );

        semanticMemory.upsertWithConflictDetection(entity, "contrastive-learning");
        vectorSearcher.upsertEntityVector(entity.id(), entity.textRepresentation());

        log.info("对比学习: 对比经验已写入, entityId={}, successId={}, failureId={}",
                entity.id(), successExp.id(), failureExp.id());
    }
}
