package com.lifepilot.knowledge.extract;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.ExtractionResult;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.scope.MemoryOriginType;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryRealityType;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.support.SqliteBusyRetry;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

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
    private static final String SCENE = LlmScene.KNOWLEDGE_EXTRACTION;

    private final @Nullable GenerationRouter generationRouter;
    private final @Nullable SemanticMemory semanticMemory;
    private final KnowledgeBaseProperties.Extraction config;
    private final PromptRegistry promptRegistry;
    private final @Nullable MemorySpaceRepository memorySpaceRepository;

    /**
     * 构造知识提取管线。
     *
     * @param generationRouter      LLM 路由器（可为 null，运行时动态配置）
     * @param semanticMemory        语义记忆（可为 null，依赖 EmbeddingRouter）
     * @param config                提取配置
     * @param promptRegistry        提示词模板注册表
     * @param memorySpaceRepository 记忆空间仓储（可为 null）
     */
    public KnowledgeExtractionPipeline(@Nullable GenerationRouter generationRouter,
                                        @Nullable SemanticMemory semanticMemory,
                                        KnowledgeBaseProperties.Extraction config,
                                        PromptRegistry promptRegistry,
                                        @Nullable MemorySpaceRepository memorySpaceRepository) {
        this.generationRouter = generationRouter;
        this.semanticMemory = semanticMemory;
        this.config = config;
        this.promptRegistry = promptRegistry;
        this.memorySpaceRepository = memorySpaceRepository;
        log.info("KnowledgeExtractionPipeline 初始化完成: enabled={}, batchSize={}, generationRouter={}, semanticMemory={}",
                config.enabled(), config.batchSize(),
                generationRouter != null ? "已配置" : "未配置",
                semanticMemory != null ? "已配置" : "未配置");
    }

    /**
     * 从分块中提取实体和关系。
     *
     * <p>按 batchSize 分批处理分块，每批调用 LLM 结构化输出提取实体和关系，
     * 通过 SemanticMemory 写入知识图谱。LLM 不可用时返回空结果。
     *
     * @param chunks     文档分块列表
     * @param doc 文档
     * @return 提取结果
     */
    public ExtractionResult extract(Document doc, List<DocumentChunk> chunks) {
        if (!config.enabled() || chunks.isEmpty()) {
            return new ExtractionResult(0, 0, List.of());
        }
        if (generationRouter == null || semanticMemory == null || memorySpaceRepository == null) {
            log.warn("GenerationRouter/SemanticMemory/MemorySpaceRepository 不可用，跳过知识提取: reason=依赖未配置");
            return new ExtractionResult(0, 0, List.of("依赖组件未配置，跳过知识提取"));
        }
        MemoryWriteContext writeContext = resolveWriteContext(doc);
        if (writeContext == null) {
            log.debug("知识提取跳过: docId={}, sourceType={}, sourceDatastoreId={}",
                    doc.id(), doc.sourceType(), doc.sourceDatastoreId());
            return new ExtractionResult(0, 0, List.of("当前文档默认不写入长期记忆"));
        }

        int totalEntities = 0;
        int totalRelations = 0;
        var warnings = new ArrayList<String>();

        for (int i = 0; i < chunks.size(); i += config.batchSize()) {
            int end = Math.min(i + config.batchSize(), chunks.size());
            var batch = chunks.subList(i, end);

            try {
                var batchResult = extractBatch(doc, batch, writeContext);
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
    private ExtractionResult extractBatch(Document doc,
                                          List<DocumentChunk> batch,
                                          MemoryWriteContext writeContext) {
        // 拼接批次内容
        var contentBuilder = new StringBuilder();
        for (var chunk : batch) {
            contentBuilder.append(chunk.content()).append("\n\n");
        }

        var prompt = buildExtractionPrompt(contentBuilder.toString());

        // 使用结构化输出提取
        var response = generationRouter.callEntity(
                SCENE,
                prompt,
                ExtractionResponse.class,
                null,
                null,
                null);

        int entityCount = 0;
        int relationCount = 0;

        // 写入实体，同时建立 name → id 映射供关系解析使用
        var entityNameToId = new HashMap<String, String>();
        if (response.entities() != null) {
            for (var entityInfo : response.entities()) {
                try {
                    var entity = toTemporalEntity(entityInfo);
                    var persisted = SqliteBusyRetry.execute(() -> semanticMemory.upsertWithConflictDetection(entity, doc.id(), writeContext));
                    entityNameToId.put(entityInfo.name(), persisted.id());
                    entityCount++;
                } catch (Exception e) {
                    log.warn("实体写入失败: name={}, error={}", entityInfo.name(), e.getMessage());
                }
            }
        }

        // 写入关系，将实体名称解析为实际 ID
        if (response.relations() != null) {
            for (var relationInfo : response.relations()) {
                try {
                    var sourceId = resolveEntityId(relationInfo.sourceEntity(), entityNameToId, writeContext);
                    var targetId = resolveEntityId(relationInfo.targetEntity(), entityNameToId, writeContext);
                    if (sourceId == null || targetId == null) {
                        log.debug("关系跳过: 无法解析实体ID, source={}, target={}",
                                relationInfo.sourceEntity(), relationInfo.targetEntity());
                        continue;
                    }
                    var relation = toTemporalRelation(relationInfo, sourceId, targetId, doc.id());
                    SqliteBusyRetry.run(() -> semanticMemory.addRelation(relation, writeContext));
                    relationCount++;
                } catch (Exception e) {
                    log.warn("关系写入失败: type={}, error={}", relationInfo.relationType(), e.getMessage());
                }
            }
        }

        return new ExtractionResult(entityCount, relationCount, List.of());
    }

    private String buildExtractionPrompt(String content) {
        return promptRegistry.render("knowledge/entity-extraction", Map.of(
                "content", content));
    }

    private TemporalEntity toTemporalEntity(ExtractionResponse.EntityInfo info) {
        var now = Instant.now();
        var type = parseEntityType(info.type());
        return new TemporalEntity(
                UUID.randomUUID().toString(), type, info.name(), info.description(),
                Map.of(), 1, true, now, null, null,
                0.7f, 0.5f, 0, null, now, now);
    }

    private TemporalRelation toTemporalRelation(ExtractionResponse.RelationInfo info,
                                                String sourceId, String targetId,
                                                String documentId) {
        var now = Instant.now();
        return new TemporalRelation(
                UUID.randomUUID().toString(),
                sourceId, targetId,
                info.relationType(),
                Math.max(0.0f, Math.min(1.0f, info.strength())),
                null, now, null, documentId, now);
    }

    /**
     * 将实体名称解析为数据库中的实际 ID。
     * 优先从当前批次的映射中查找，找不到则从数据库按名称查找。
     */
    private String resolveEntityId(String entityName,
                                   Map<String, String> nameToId,
                                   MemoryWriteContext writeContext) {
        // 优先从当前批次映射查找
        var id = nameToId.get(entityName);
        if (id != null) {
            return id;
        }
        MemoryReadFilter readFilter = buildReadFilter(writeContext);
        // 回退：从数据库按名称查找（遍历所有类型）
        for (var type : EntityType.values()) {
            var found = semanticMemory.findCurrentByNameAndType(entityName, type, readFilter);
            if (found.isPresent()) {
                nameToId.put(entityName, found.get().id());
                return found.get().id();
            }
        }
        return null;
    }

    private MemoryReadFilter buildReadFilter(MemoryWriteContext writeContext) {
        return MemoryReadFilter.of(
                writeContext.spaceId() != null ? List.of(writeContext.spaceId()) : List.of(),
                writeContext.memoryScope() != null ? List.of(writeContext.memoryScope()) : List.of()
        );
    }

    private EntityType parseEntityType(String type) {
        try {
            return EntityType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EntityType.CUSTOM;
        }
    }

    private MemoryWriteContext resolveWriteContext(Document doc) {
        if (doc.sourceType() == DocumentSourceType.FILE
                && (doc.sourceDatastoreId() == null || doc.sourceDatastoreId().isBlank())) {
            return null;
        }
        if (doc.sourceDatastoreId() != null && !doc.sourceDatastoreId().isBlank()) {
            var domainSpace = memorySpaceRepository.ensureDatastoreDomainSpace(doc.sourceDatastoreId());
            return new MemoryWriteContext(
                    domainSpace.id(),
                    MemoryScope.DOMAIN_MEMORY,
                    doc.sourceType() == DocumentSourceType.DATASTORE_DOCUMENT
                            ? MemoryOriginType.DATASTORE_DOCUMENT
                            : MemoryOriginType.KNOWLEDGE_BASE_DOCUMENT,
                    MemoryRealityType.UNKNOWN,
                    doc.id(),
                    doc.id(),
                    null,
                    null,
                    null,
                    doc.id(),
                    doc.knowledgeBaseId(),
                    doc.sourceDatastoreId(),
                    doc.sourceCollectionId()
            );
        }
        if (doc.sourceType() == DocumentSourceType.DATASTORE_DOCUMENT) {
            var domainSpace = memorySpaceRepository.ensureKnowledgeBaseDomainSpace(doc.knowledgeBaseId());
            return new MemoryWriteContext(
                    domainSpace.id(),
                    MemoryScope.DOMAIN_MEMORY,
                    MemoryOriginType.DATASTORE_DOCUMENT,
                    MemoryRealityType.UNKNOWN,
                    doc.id(),
                    doc.id(),
                    null,
                    null,
                    null,
                    doc.id(),
                    doc.knowledgeBaseId(),
                    null,
                    doc.sourceCollectionId()
            );
        }
        return null;
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
