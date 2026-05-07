package com.lifepilot.memory.experience;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.support.SqliteBusyRetry;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
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
    private final MemoryProperties.Experience.Contrastive config;

    public ContrastiveLearner(SemanticMemory semanticMemory,
                               VectorSearcher vectorSearcher,
                               @Nullable GenerationRouter generationRouter,
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

            // 增强源经验（成功经验）
            enrichSourceExperience(insight, successExp, failureExp);

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
     * 增强源经验 — 将对比洞察回写到成功经验的 lessons 列表中。
     *
     * <p>在成功经验的 properties 中追加带 "[对比]" 前缀的 lesson 条目，
     * 并标记 contrastiveEnriched=true，避免创建独立的对比洞察实体。</p>
     */
    private void enrichSourceExperience(ContrastiveInsight insight,
                                         TemporalEntity successExp,
                                         TemporalEntity failureExp) {
        // 复制 properties（TemporalEntity compact constructor 会 Map.copyOf，需可变副本）
        var updatedProps = new LinkedHashMap<>(successExp.properties());

        // 读取已有 lessons 列表，可能为 null
        @SuppressWarnings("unchecked")
        var existingLessons = (List<String>) updatedProps.get("lessons");
        var lessons = new ArrayList<>(existingLessons != null ? existingLessons : List.<String>of());

        // 追加对比教训列表（优先使用 contrastiveLessons，内容更具操作指导性）
        boolean hasContrastiveLessons = false;
        if (insight.contrastiveLessons() != null) {
            for (String lesson : insight.contrastiveLessons()) {
                if (lesson != null && !lesson.isBlank()) {
                    lessons.add("[对比] " + lesson);
                    hasContrastiveLessons = true;
                }
            }
        }
        // 兜底：仅在 contrastiveLessons 为空时追加失败根因和成功因素
        if (!hasContrastiveLessons) {
            if (insight.failureReason() != null && !insight.failureReason().isBlank()) {
                lessons.add("[对比] 失败原因: " + insight.failureReason());
            }
            if (insight.successFactor() != null && !insight.successFactor().isBlank()) {
                lessons.add("[对比] 成功因素: " + insight.successFactor());
            }
        }

        updatedProps.put("lessons", lessons);
        updatedProps.put("contrastiveEnriched", true);

        // 构建更新后的不可变实体
        var enriched = new TemporalEntity(
                successExp.id(),
                successExp.type(),
                successExp.name(),
                successExp.description(),
                updatedProps,
                successExp.version(),
                successExp.isCurrent(),
                successExp.validFrom(),
                successExp.validTo(),
                successExp.sourceConversationId(),
                successExp.extractionConfidence(),
                successExp.importanceScore(),
                successExp.accessCount(),
                successExp.lastAccessedAt(),
                successExp.createdAt(),
                Instant.now()
        );

        SqliteBusyRetry.run(() -> {
            semanticMemory.upsertWithConflictDetection(enriched, "contrastive-learning");
        });

        log.info("对比学习: 成功经验已增强, successId={}, failureId={}, 新增lessons={}条",
                successExp.id(), failureExp.id(), lessons.size() - (existingLessons != null ? existingLessons.size() : 0));
    }
}
