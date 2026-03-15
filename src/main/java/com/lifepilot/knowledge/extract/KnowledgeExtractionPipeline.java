package com.lifepilot.knowledge.extract;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.ExtractionResult;
import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 知识提取管线 — 从文档分块中提取实体和关系写入 L3 语义记忆。
 *
 * <p>使用 LLM 结构化输出提取实体和关系，通过 {@link SemanticMemory} 写入知识图谱。
 * LLM 不可用时优雅降级，返回空结果。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class KnowledgeExtractionPipeline {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractionPipeline.class);
    private static final String SCENE = "knowledge_extraction";

    private final LlmRouter llmRouter;
    private final SemanticMemory semanticMemory;
    private final KnowledgeBaseProperties.Extraction config;

    /**
     * 构造知识提取管线。
     *
     * @param llmRouter      LLM 路由器
     * @param semanticMemory 语义记忆
     * @param config         提取配置
     */
    public KnowledgeExtractionPipeline(LlmRouter llmRouter,
                                        SemanticMemory semanticMemory,
                                        KnowledgeBaseProperties.Extraction config) {
        this.llmRouter = llmRouter;
        this.semanticMemory = semanticMemory;
        this.config = config;
        log.info("KnowledgeExtractionPipeline 初始化完成: enabled={}, batchSize={}",
                config.enabled(), config.batchSize());
    }

    /**
     * 从分块中提取实体和关系。
     *
     * <p>按 batchSize 分批处理分块，每批调用 LLM 结构化输出提取实体和关系，
     * 通过 SemanticMemory 写入知识图谱。LLM 不可用时返回空结果。
     *
     * @param chunks     文档分块列表
     * @param documentId 文档 ID（用作 conversationId）
     * @return 提取结果
     */
    public ExtractionResult extract(List<DocumentChunk> chunks, String documentId) {
        if (!config.enabled() || chunks.isEmpty()) {
            return new ExtractionResult(0, 0, List.of());
        }

        int totalEntities = 0;
        int totalRelations = 0;
        var warnings = new ArrayList<String>();

        for (int i = 0; i < chunks.size(); i += config.batchSize()) {
            int end = Math.min(i + config.batchSize(), chunks.size());
            var batch = chunks.subList(i, end);

            try {
                var batchResult = extractBatch(batch, documentId);
                totalEntities += batchResult.entityCount();
                totalRelations += batchResult.relationCount();
                warnings.addAll(batchResult.warnings());
            } catch (LlmUnavailableException e) {
                log.warn("LLM 不可用，跳过剩余知识提取: {}", e.getMessage());
                warnings.add("LLM 不可用，跳过知识提取");
                break;
            } catch (Exception e) {
                log.warn("知识提取批次失败，跳过: error={}", e.getMessage());
                warnings.add("批次提取失败: " + e.getMessage());
            }
        }

        log.info("知识提取完成: entities={}, relations={}, warnings={}",
                totalEntities, totalRelations, warnings.size());
        return new ExtractionResult(totalEntities, totalRelations, List.copyOf(warnings));
    }

    /**
     * 提取单批次分块中的实体和关系。
     */
    private ExtractionResult extractBatch(List<DocumentChunk> batch, String documentId) {
        // 拼接批次内容
        var contentBuilder = new StringBuilder();
        for (var chunk : batch) {
            contentBuilder.append(chunk.content()).append("\n\n");
        }

        var prompt = buildExtractionPrompt(contentBuilder.toString());

        // 使用结构化输出提取
        var response = llmRouter.callEntity(LlmRequest.of(SCENE, prompt), ExtractionResponse.class);

        int entityCount = 0;
        int relationCount = 0;

        // 写入实体
        if (response.entities() != null) {
            for (var entityInfo : response.entities()) {
                try {
                    var entity = toTemporalEntity(entityInfo);
                    semanticMemory.upsertWithConflictDetection(entity, documentId);
                    entityCount++;
                } catch (Exception e) {
                    log.warn("实体写入失败: name={}, error={}", entityInfo.name(), e.getMessage());
                }
            }
        }

        // 写入关系
        if (response.relations() != null) {
            for (var relationInfo : response.relations()) {
                try {
                    var relation = toTemporalRelation(relationInfo, documentId);
                    semanticMemory.addRelation(relation);
                    relationCount++;
                } catch (Exception e) {
                    log.warn("关系写入失败: type={}, error={}", relationInfo.relationType(), e.getMessage());
                }
            }
        }

        return new ExtractionResult(entityCount, relationCount, List.of());
    }

    private String buildExtractionPrompt(String content) {
        return """
                请从以下文本中提取实体和关系。
                
                实体类型包括：PERSON, ORGANIZATION, PLACE, EVENT, PROJECT, TOPIC, PREFERENCE, HABIT, GOAL, SKILL, CUSTOM
                
                请以 JSON 格式返回：
                {
                  "entities": [{"name": "实体名", "type": "PERSON", "description": "描述"}],
                  "relations": [{"sourceEntity": "实体A", "targetEntity": "实体B", "relationType": "关系类型", "strength": 0.8}]
                }
                
                文本内容：
                %s""".formatted(content);
    }

    private TemporalEntity toTemporalEntity(ExtractionResponse.EntityInfo info) {
        var now = Instant.now();
        var type = parseEntityType(info.type());
        return new TemporalEntity(
                UUID.randomUUID().toString(), type, info.name(), info.description(),
                Map.of(), 1, true, now, null, null,
                0.7f, 0.5f, 0, null, now, now);
    }

    private TemporalRelation toTemporalRelation(ExtractionResponse.RelationInfo info, String documentId) {
        var now = Instant.now();
        return new TemporalRelation(
                UUID.randomUUID().toString(),
                info.sourceEntity(), info.targetEntity(),
                info.relationType(),
                Math.max(0.0f, Math.min(1.0f, info.strength())),
                null, now, null, documentId, now);
    }

    private EntityType parseEntityType(String type) {
        try {
            return EntityType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EntityType.CUSTOM;
        }
    }

    /**
     * LLM 结构化输出响应。
     */
    public record ExtractionResponse(
            List<EntityInfo> entities,
            List<RelationInfo> relations
    ) {
        public record EntityInfo(String name, String type, String description) {}
        public record RelationInfo(String sourceEntity, String targetEntity,
                                   String relationType, float strength) {}
    }
}
