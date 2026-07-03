package com.lifepilot.agent.learning.extraction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>LLM 调用失败、超时或返回不符合关系 JSON 契约时抛出异常，由上层记录为关系抽取阶段失败。</p>
 *
 * @author zsg
 * @since 2026-06-06
 */
public class RelationExtractionStep {

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
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter 不能为空");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry 不能为空");
        this.relationTimeoutSeconds = Objects.requireNonNull(properties, "properties 不能为空")
                .getExtraction().getRelationTimeoutSeconds();
        if (relationTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("关系抽取超时秒数必须大于 0: " + relationTimeoutSeconds);
        }
    }

    /**
     * 抽取实体间关系。
     *
     * @param conversationText 对话文本（含"用户:"/"AI:"前缀）
     * @param entityNames      本轮已知实体名称清单（带类型描述，供 LLM 约束端点）
     * @return 抽取出的关系列表；端点不足时返回空列表
     */
    public List<ExtractedRelation> extract(String conversationText, List<String> entityNames) {
        if (conversationText == null || conversationText.isBlank()) {
            throw new IllegalArgumentException("关系抽取对话文本不能为空");
        }
        Objects.requireNonNull(entityNames, "关系抽取实体清单不能为空");
        var validatedEntityNames = entityNames.stream()
                .map(name -> requireText(name, "关系抽取实体名称不能为空"))
                .toList();
        if (validatedEntityNames.size() < 2) {
            // 少于 2 个实体不可能存在边，省去一次 LLM 调用
            return List.of();
        }
        String entityList = String.join("\n", validatedEntityNames.stream().map(n -> "- " + n).toList());
        String prompt = promptRegistry.render("semantic/relation-extraction", Map.of(
                "entityList", entityList,
                "conversationText", conversationText));
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("关系抽取 Prompt 渲染为空");
        }
        var future = CompletableFuture.supplyAsync(() ->
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
                .orTimeout(relationTimeoutSeconds, TimeUnit.SECONDS);
        var response = Objects.requireNonNull(awaitRelationResponse(future), "关系抽取返回为空");
        return parseRelations(response.content());
    }

    private LlmResponse awaitRelationResponse(CompletableFuture<LlmResponse> future) {
        try {
            return future.join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                throw new IllegalStateException("关系抽取超时: timeoutSeconds=" + relationTimeoutSeconds, cause);
            }
            throw new IllegalStateException("关系抽取 LLM 调用失败: " + cause.getMessage(), cause);
        }
    }

    private static String requireText(@Nullable String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(message + "，且不能包含首尾空白: " + value);
        }
        return value;
    }

    /** 解析 LLM 返回的关系数组。 */
    private List<ExtractedRelation> parseRelations(@Nullable String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("关系抽取 LLM 返回空内容");
        }
        try {
            return MAPPER.readValue(content,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, ExtractedRelation.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("关系数组解析失败: " + e.getOriginalMessage(), e);
        }
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
            if (sourceName == null || sourceName.isBlank()) {
                throw new IllegalArgumentException("sourceName 不能为空");
            }
            if (!sourceName.equals(sourceName.trim())) {
                throw new IllegalArgumentException("sourceName 不能包含首尾空白");
            }
            if (targetName == null || targetName.isBlank()) {
                throw new IllegalArgumentException("targetName 不能为空");
            }
            if (!targetName.equals(targetName.trim())) {
                throw new IllegalArgumentException("targetName 不能包含首尾空白");
            }
            if (relationType == null || relationType.isBlank()) {
                throw new IllegalArgumentException("relationType 不能为空");
            }
            if (!relationType.equals(relationType.trim())) {
                throw new IllegalArgumentException("relationType 不能包含首尾空白");
            }
            if (!Float.isFinite(strength) || strength < 0.0f || strength > 1.0f) {
                throw new IllegalArgumentException("strength 必须在 [0,1] 范围内");
            }
            if (evidence != null && !evidence.equals(evidence.trim())) {
                throw new IllegalArgumentException("evidence 不能包含首尾空白");
            }
            this.sourceName = sourceName;
            this.targetName = targetName;
            this.relationType = relationType;
            this.strength = strength;
            this.evidence = evidence;
        }
    }

}
