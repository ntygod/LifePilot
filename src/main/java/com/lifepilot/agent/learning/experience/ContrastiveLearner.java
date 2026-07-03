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
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
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
    private final AgentLearningProperties.Experience.Contrastive config;

    public ContrastiveLearner(SemanticMemory semanticMemory,
                               VectorSearcher vectorSearcher,
                               GenerationRouter generationRouter,
                               PromptRegistry promptRegistry,
                               AgentLearningProperties properties) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "vectorSearcher 不能为空");
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter 不能为空");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry 不能为空");
        this.config = Objects.requireNonNull(properties, "properties 不能为空")
                .getExperience().getContrastive();
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

        // 获取新经验的 success 标志
        requireExperience(newExperience);
        boolean newSuccess = requiredSuccess(newExperience);

        // 搜索相似经验
        var searchResults = requireVectorResults(vectorSearcher.searchEntities(
                newExperience.textRepresentation(), 5,
                similarityThreshold(config.getSimilarityThreshold(), "对比学习相似度阈值")));

        // 过滤 success 标志相反的经验
        TemporalEntity matchedEntity = null;
        for (var result : searchResults) {
            // 排除自身
            if (result.entityId().equals(newExperience.id())) continue;

            var optEntity = semanticMemory.findById(result.entityId());
            if (optEntity == null || optEntity.isEmpty()) {
                throw new IllegalStateException("对比学习: 向量候选实体不存在: " + result.entityId());
            }

            var entity = optEntity.get();
            // 必须是 EXPERIENCE 类型
            if (entity.type() != EntityType.EXPERIENCE) continue;

            boolean entitySuccess = requiredSuccess(entity);
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

        var insight = analyzeContrast(successExp, failureExp);

        // 产出独立派生洞察实体（带血缘，源失效可级联）
        createContrastiveInsight(insight, successExp, failureExp);
    }

    /** 调用 LLM 进行对比分析。 */
    private ContrastiveInsight analyzeContrast(TemporalEntity successExp, TemporalEntity failureExp) {
        var vars = Map.<String, Object>of(
                "successScenario", successExp.name(),
                "successStrategy", requireNonBlank(successExp.description(), "对比学习经验描述"),
                "successLessons", String.valueOf(requiredStringListProperty(successExp, "lessons")),
                "successTools", String.valueOf(requiredStringListProperty(successExp, "toolsUsed")),
                "failureScenario", failureExp.name(),
                "failureStrategy", requireNonBlank(failureExp.description(), "对比学习经验描述"),
                "failureLessons", String.valueOf(requiredStringListProperty(failureExp, "lessons")),
                "failureTools", String.valueOf(requiredStringListProperty(failureExp, "toolsUsed"))
        );
        String prompt = promptRegistry.render(PROMPT_KEY, vars);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("对比学习 prompt 渲染结果不能为空");
        }
        Duration timeout = Duration.ofSeconds(positive(config.getLlmTimeoutSeconds(), "对比学习 LLM 超时秒数"));
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
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("对比学习 LLM 响应不能为空");
        }
        var insight = JsonOutputParser.parse(response.content(), ContrastiveInsight.class);
        if (insight == null || insight.failureReason() == null || insight.failureReason().isBlank()
                || insight.successFactor() == null || insight.successFactor().isBlank()) {
            throw new IllegalStateException("对比学习 LLM 响应缺少 failureReason 或 successFactor");
        }
        return insight;
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
                null, true, List.of(successExp.id(), failureExp.id()),
                MemoryEvidenceKind.DERIVED, MemoryTrustLevel.DERIVED, trustScore, 1, null);

        SqliteBusyRetry.run(() ->
                semanticMemory.upsertWithConflictDetection(
                        derived,
                        "contrastive-learning",
                        MemoryWriteContext.consolidation("contrastive-learning")));

        log.info("对比学习: 产出对比洞察派生实体, id={}, sources=[{}, {}], lessons={}条",
                derived.id(), successExp.id(), failureExp.id(), lessons.size());
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于 0: " + value);
        }
        return value;
    }

    private static void requireExperience(TemporalEntity entity) {
        Objects.requireNonNull(entity, "对比学习新经验不能为空");
        requireCanonicalId(entity.id(), "对比学习经验 ID");
        if (entity.type() != EntityType.EXPERIENCE) {
            throw new IllegalArgumentException("对比学习只能处理 EXPERIENCE 实体: " + entity.id());
        }
        requireNonBlank(entity.name(), "对比学习经验名称");
        requireNonBlank(entity.description(), "对比学习经验描述");
        requiredStringListProperty(entity, "lessons");
        requiredStringListProperty(entity, "toolsUsed");
    }

    private static boolean requiredSuccess(TemporalEntity entity) {
        Object success = entity.properties().get("success");
        if (!(success instanceof Boolean value)) {
            throw new IllegalStateException("对比学习: EXPERIENCE success 必须是 boolean, entityId="
                    + entity.id());
        }
        return value;
    }

    private static List<?> requiredStringListProperty(TemporalEntity entity, String key) {
        Object value = entity.properties().get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("对比学习: EXPERIENCE " + key + " 必须是数组, entityId="
                    + entity.id());
        }
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalStateException("对比学习: EXPERIENCE " + key + " 只能包含非空字符串, entityId="
                        + entity.id());
            }
            if (!text.equals(text.trim())) {
                throw new IllegalStateException("对比学习: EXPERIENCE " + key + " 不能包含首尾空白, entityId="
                        + entity.id());
            }
        }
        return list;
    }

    private static List<VectorSearchResult> requireVectorResults(List<VectorSearchResult> results) {
        if (results == null) {
            throw new IllegalStateException("对比学习: 向量搜索结果不能为空");
        }
        for (VectorSearchResult result : results) {
            if (result == null) {
                throw new IllegalStateException("对比学习: 向量搜索结果不能包含 null 元素");
            }
            requireCanonicalId(result.entityId(), "对比学习向量候选 ID");
            if (!Float.isFinite(result.similarity())
                    || result.similarity() < 0.0f
                    || result.similarity() > 1.0f) {
                throw new IllegalStateException("对比学习: 向量相似度必须在 [0,1] 范围内: "
                        + result.similarity());
            }
        }
        return results;
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
}
