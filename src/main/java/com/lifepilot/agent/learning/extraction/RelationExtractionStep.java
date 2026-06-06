package com.lifepilot.agent.learning.extraction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 对话关系抽取步骤 —— 在 AUDN 实体写入后追加执行的二阶段，
 * 给定对话文本与本轮已知实体清单，调用 LLM 抽取实体间关系。
 *
 * <p>设计为独立步骤而非扩展 AUDN：AUDN prompt 已聚焦"用户个人事实"，
 * 关系抽取在实体持久化、ID 已知后运行，名称→ID 解析最可靠（与 KnowledgeExtractionPipeline 同构）。</p>
 *
 * <p>任何超时/异常均降级为空列表，不影响实体写入与主对话流程。</p>
 *
 * @author zsg
 * @since 2026-06-06
 */
public class RelationExtractionStep {

    private static final Logger log = LoggerFactory.getLogger(RelationExtractionStep.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final ExecutorService VIRTUAL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final int relationTimeoutSeconds;

    public RelationExtractionStep(GenerationRouter generationRouter,
                                  PromptRegistry promptRegistry,
                                  AgentLearningProperties properties) {
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.relationTimeoutSeconds = properties.getExtraction().getRelationTimeoutSeconds();
    }

    /**
     * 抽取实体间关系。
     *
     * @param conversationText 对话文本（含"用户:"/"AI:"前缀）
     * @param entityNames      本轮已知实体名称清单（带类型描述，供 LLM 约束端点）
     * @return 抽取出的关系列表；端点不足、超时或失败时返回空列表
     */
    public List<ExtractedRelation> extract(String conversationText, List<String> entityNames) {
        if (conversationText == null || conversationText.isBlank()) {
            return List.of();
        }
        if (entityNames == null || entityNames.size() < 2) {
            // 少于 2 个实体不可能存在边，省去一次 LLM 调用
            return List.of();
        }
        String entityList = String.join("\n", entityNames.stream().map(n -> "- " + n).toList());
        String prompt = promptRegistry.render("semantic/relation-extraction", Map.of(
                "entityList", entityList,
                "conversationText", conversationText));
        try {
            var result = CompletableFuture.supplyAsync(() ->
                            generationRouter.call(
                                    LlmScene.KNOWLEDGE_EXTRACTION,
                                    prompt,
                                    null,
                                    null,
                                    null,
                                    GenerationCapability.CHAT,
                                    Duration.ofSeconds(relationTimeoutSeconds),
                                    true),  // skipCache：每轮对话内容不同
                            VIRTUAL_EXECUTOR)
                    .orTimeout(relationTimeoutSeconds, TimeUnit.SECONDS)
                    .thenApply(response -> parseRelations(response.content()))
                    .join();
            return result != null ? result : List.of();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                log.warn("关系抽取超时: timeoutSeconds={}", relationTimeoutSeconds);
            } else {
                log.warn("关系抽取 LLM 调用失败: error={}", cause.getMessage());
            }
            return List.of();
        }
    }

    /** 解析 LLM 返回的关系列表，兼容数组 [...] 与对象 {"relations":[...]} 两种格式。 */
    private List<ExtractedRelation> parseRelations(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String repaired = JsonOutputParser.repairJson(content);
        if (repaired.stripLeading().startsWith("[")) {
            try {
                return MAPPER.readValue(repaired,
                        MAPPER.getTypeFactory().constructCollectionType(List.class, ExtractedRelation.class));
            } catch (Exception e) {
                log.warn("关系抽取数组格式解析失败，尝试对象格式: {}", e.getMessage());
            }
        }
        var wrapped = JsonOutputParser.parse(repaired, ExtractedRelationList.class);
        return wrapped != null && wrapped.relations() != null ? wrapped.relations() : List.of();
    }

    /** 抽取出的单条关系。 */
    public record ExtractedRelation(
            String sourceName,
            String targetName,
            String relationType,
            float strength,
            @Nullable String evidence) {

        @JsonCreator
        public ExtractedRelation(
                @JsonProperty("sourceName") String sourceName,
                @JsonProperty("targetName") String targetName,
                @JsonProperty("relationType") String relationType,
                @JsonProperty("strength") float strength,
                @JsonProperty("evidence") @Nullable String evidence) {
            this.sourceName = sourceName;
            this.targetName = targetName;
            this.relationType = relationType;
            this.strength = Math.max(0.0f, Math.min(1.0f, strength));
            this.evidence = evidence;
        }
    }

    /** 对象包裹格式 {"relations": [...]}。 */
    record ExtractedRelationList(List<ExtractedRelation> relations) {
        @JsonCreator
        ExtractedRelationList(@JsonProperty("relations") List<ExtractedRelation> relations) {
            this.relations = relations;
        }
    }
}
