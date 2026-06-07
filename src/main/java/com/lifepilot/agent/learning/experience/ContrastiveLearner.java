package com.lifepilot.agent.learning.experience;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

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
    @Nullable
    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final AgentLearningProperties.Experience.Contrastive config;

    public ContrastiveLearner(SemanticMemory semanticMemory,
                               VectorSearcher vectorSearcher,
                               @Nullable GenerationRouter generationRouter,
                               PromptRegistry promptRegistry,
                               AgentLearningProperties AgentLearningProperties) {
        this.semanticMemory = semanticMemory;
        this.vectorSearcher = vectorSearcher;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.config = AgentLearningProperties.getExperience().getContrastive();
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
        if (generationRouter == null) {
            log.debug("对比学习: GenerationRouter 不可用，跳过");
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

            // 产出独立派生洞察实体（带血缘，源失效可级联）
            createContrastiveInsight(insight, successExp, failureExp);

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
                    LlmScene.BACKGROUND_ANALYSIS,
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

    /**
     * 产出独立的对比洞察派生实体 — 取代原"原地增强成功经验"做法（thought/记忆链路 #4）。
     *
     * <p>新建一个 {@code isDerived=true}、{@code derivationSources=[successId, failureId]} 的
     * 派生 EXPERIENCE 实体，properties 标 {@code insightType=CONTRASTIVE}。这样任一源经验失效时，
     * {@code DerivedEntityListener} 可经 derivation_sources 反查级联失活该洞察，避免无血缘的陈旧洞察残留。</p>
     */
    private void createContrastiveInsight(ContrastiveInsight insight,
                                          TemporalEntity successExp,
                                          TemporalEntity failureExp) {
        var lessons = new ArrayList<String>();
        if (insight.contrastiveLessons() != null) {
            for (String lesson : insight.contrastiveLessons()) {
                if (lesson != null && !lesson.isBlank()) {
                    lessons.add(lesson);
                }
            }
        }
        if (lessons.isEmpty()) {
            if (insight.failureReason() != null && !insight.failureReason().isBlank()) {
                lessons.add("失败原因: " + insight.failureReason());
            }
            if (insight.successFactor() != null && !insight.successFactor().isBlank()) {
                lessons.add("成功因素: " + insight.successFactor());
            }
        }

        var props = new LinkedHashMap<String, Object>();
        props.put("insightType", "CONTRASTIVE");
        props.put("lessons", lessons);
        if (insight.successFactor() != null) props.put("successFactor", insight.successFactor());
        if (insight.failureReason() != null) props.put("failureReason", insight.failureReason());

        var now = Instant.now();
        String name = "对比洞察: " + successExp.name() + " ↔ " + failureExp.name();
        String desc = "成功因素: " + (insight.successFactor() != null ? insight.successFactor() : "")
                + "；失败原因: " + (insight.failureReason() != null ? insight.failureReason() : "");
        float trustScore = MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.DERIVED, 0.6f);

        var derived = new TemporalEntity(
                UUID.randomUUID().toString(),
                EntityType.EXPERIENCE,
                name,
                desc,
                props,
                1, true, now, null, successExp.sourceConversationId(),
                0.6f,
                Math.max(0.5f, successExp.importanceScore()),
                0, null, now, now,
                LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT,
                null, true, List.of(successExp.id(), failureExp.id()))
                .withQuality(MemoryEvidenceKind.DERIVED, MemoryTrustLevel.DERIVED, trustScore, 1, null);

        SqliteBusyRetry.run(() ->
                semanticMemory.upsertWithConflictDetection(derived, "contrastive-learning"));

        log.info("对比学习: 产出对比洞察派生实体, id={}, sources=[{}, {}], lessons={}条",
                derived.id(), successExp.id(), failureExp.id(), lessons.size());
    }
}
