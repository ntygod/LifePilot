package com.lifepilot.knowledge.extract;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.ExtractionResult;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.memory.store.entity.TemporalEntity;
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
 * LLM 不可用时停止当前提取批次并返回警告。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class KnowledgeExtractionPipeline {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractionPipeline.class);
    private static final String SCENE = LlmScene.KNOWLEDGE_EXTRACTION;

    private final GenerationRouter generationRouter;
    private final SemanticMemory semanticMemory;
    private final KnowledgeBaseProperties.Extraction config;
    private final PromptRegistry promptRegistry;
    private final MemorySpaceRepository memorySpaceRepository;

    /**
     * 构造知识提取管线。
     *
     * @param generationRouter      LLM 路由器
     * @param semanticMemory        语义记忆
     * @param config                提取配置
     * @param promptRegistry        提示词模板注册表
     * @param memorySpaceRepository 记忆空间仓储
     */
    public KnowledgeExtractionPipeline(GenerationRouter generationRouter,
                                        SemanticMemory semanticMemory,
                                        KnowledgeBaseProperties.Extraction config,
                                        PromptRegistry promptRegistry,
                                        MemorySpaceRepository memorySpaceRepository) {
        this.generationRouter = Objects.requireNonNull(generationRouter, "GenerationRouter 不能为空");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "SemanticMemory 不能为空");
        this.config = Objects.requireNonNull(config, "知识提取配置不能为空");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "PromptRegistry 不能为空");
        this.memorySpaceRepository = Objects.requireNonNull(memorySpaceRepository, "MemorySpaceRepository 不能为空");
        log.info("KnowledgeExtractionPipeline 初始化完成: enabled={}, batchSize={}",
                config.enabled(), config.batchSize());
    }

    /**
     * 从分块中提取实体和关系。
     *
     * <p>按 batchSize 分批处理分块，每批调用 LLM 结构化输出提取实体和关系，
     * 通过 SemanticMemory 写入知识图谱。LLM 不可用时返回警告。
     *
     * @param chunks     文档分块列表
     * @param doc 文档
     * @return 提取结果
     */
    public ExtractionResult extract(Document doc, List<DocumentChunk> chunks) {
        if (!config.enabled() || chunks.isEmpty()) {
            return new ExtractionResult(0, 0, List.of());
        }
        MemoryWriteContext writeContext = resolveWriteContext(doc);

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
        var prompt = buildExtractionPrompt(batch);

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
        // 知识库文档不提取用户属性类型（PREFERENCE/HABIT/GOAL），这些只应从对话中产生。
        var entityNameToId = new HashMap<String, String>();
        if (response.entities() != null) {
            for (var entityInfo : response.entities()) {
                var entity = toTemporalEntity(entityInfo);
                if (isUserAttributeType(entity.type())) {
                    log.debug("知识提取: 跳过知识库文档中的用户属性实体, name={}, type={}", entity.name(), entity.type());
                    continue;
                }
                var entityWriteContext = withChunkEvidence(doc, writeContext,
                        resolveSourceChunkId(entityInfo.sourceChunkId(), batch));
                var persisted = SqliteBusyRetry.execute(() -> semanticMemory.upsertWithConflictDetection(
                        entity, doc.id(), entityWriteContext));
                entityNameToId.put(entityInfo.name(), persisted.id());
                entityCount++;
            }
        }

        // 写入关系，将实体名称解析为实际 ID
        if (response.relations() != null) {
            for (var relationInfo : response.relations()) {
                var sourceId = resolveEntityId(relationInfo.sourceEntity(), entityNameToId, writeContext);
                var targetId = resolveEntityId(relationInfo.targetEntity(), entityNameToId, writeContext);
                if (sourceId == null || targetId == null) {
                    log.debug("关系跳过: 无法解析实体ID, source={}, target={}",
                            relationInfo.sourceEntity(), relationInfo.targetEntity());
                    continue;
                }
                var relation = toTemporalRelation(relationInfo, sourceId, targetId, doc.id());
                var relationWriteContext = withChunkEvidence(doc, writeContext,
                        resolveSourceChunkId(relationInfo.sourceChunkId(), batch));
                SqliteBusyRetry.run(() -> semanticMemory.addRelation(relation, relationWriteContext));
                relationCount++;
            }
        }

        return new ExtractionResult(entityCount, relationCount, List.of());
    }

    private String buildExtractionPrompt(List<DocumentChunk> batch) {
        var contentBuilder = new StringBuilder();
        for (var chunk : batch) {
            contentBuilder.append("<chunk id=\"")
                    .append(chunk.id())
                    .append("\">\n")
                    .append(chunk.content())
                    .append("\n</chunk>\n\n");
        }
        return promptRegistry.render("knowledge/entity-extraction", Map.of(
                "content", contentBuilder.toString().strip()));
    }

    private TemporalEntity toTemporalEntity(ExtractionResponse.EntityInfo info) {
        var now = Instant.now();
        var type = parseEntityType(info.type());
        float extractionConfidence = 0.7f;
        MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.DOCUMENT_GROUNDED;
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, extractionConfidence);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        return new TemporalEntity(
                UUID.randomUUID().toString(), type, info.name(), info.description(),
                Map.of(), 1, true, now, null, null,
                extractionConfidence, 0.5f, 0, null, now, now,
                LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT,
                null, false, List.of(),
                evidenceKind, trustLevel, trustScore, 1, now);
    }

    private TemporalRelation toTemporalRelation(ExtractionResponse.RelationInfo info,
                                                String sourceId, String targetId,
                                                String documentId) {
        var now = Instant.now();
        float strength = info.strength();
        if (!(strength >= 0.0f && strength <= 1.0f)) {
            throw new IllegalArgumentException("知识关系强度必须在 [0,1] 范围内: " + strength);
        }
        float relTrust = MemoryQualityPolicy.trustScoreFor(MemoryEvidenceKind.DOCUMENT_GROUNDED, strength);
        return new TemporalRelation(
                UUID.randomUUID().toString(),
                sourceId, targetId,
                info.relationType(),
                strength,
                null, now, null, documentId, now,
                MemoryEvidenceKind.DOCUMENT_GROUNDED,
                MemoryQualityPolicy.trustLevelFor(MemoryEvidenceKind.DOCUMENT_GROUNDED, relTrust),
                relTrust);
    }

    private String resolveSourceChunkId(@Nullable String rawChunkId, List<DocumentChunk> batch) {
        if (batch == null || batch.isEmpty()) {
            throw new IllegalArgumentException("知识提取批次不能为空");
        }
        if (rawChunkId != null && !rawChunkId.isBlank()) {
            String normalized = rawChunkId.trim();
            for (var chunk : batch) {
                if (chunk.id().equals(normalized)) {
                    return normalized;
                }
            }
            throw new IllegalArgumentException("知识提取结果引用了不存在的 sourceChunkId: " + rawChunkId);
        }
        if (batch.size() == 1) {
            return batch.getFirst().id();
        }
        throw new IllegalArgumentException("知识提取结果缺少 sourceChunkId，无法在多分块批次中定位证据");
    }

    private MemoryWriteContext withChunkEvidence(Document doc,
                                                 MemoryWriteContext base,
                                                 String chunkId) {
        return new MemoryWriteContext(
                base.spaceId(),
                base.memoryScope(),
                base.originType(),
                base.realityType(),
                doc.id() + "#" + chunkId,
                doc.id(),
                base.sourceSessionId(),
                base.sourceTurnId(),
                chunkId,
                doc.id(),
                doc.knowledgeBaseId()
        );
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

    /** 用户属性类型 — 只应从对话中提取，不从领域数据文档中提取。 */
    private static boolean isUserAttributeType(EntityType type) {
        return type == EntityType.PREFERENCE || type == EntityType.HABIT || type == EntityType.GOAL;
    }

    private EntityType parseEntityType(String type) {
        try {
            return EntityType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EntityType.CUSTOM;
        }
    }

    private MemoryWriteContext resolveWriteContext(Document doc) {
        var domainSpace = memorySpaceRepository.ensureKnowledgeBaseDomainSpace(doc.knowledgeBaseId());
        return new MemoryWriteContext(
                domainSpace.id(),
                MemoryScope.DOMAIN_MEMORY,
                MemoryOriginType.KNOWLEDGE_BASE_DOCUMENT,
                MemoryRealityType.UNKNOWN,
                doc.id(),
                doc.id(),
                null,
                null,
                null,
                doc.id(),
                doc.knowledgeBaseId()
        );
    }

    /**
     * LLM 结构化输出响应。
     */
    public record ExtractionResponse(
            List<EntityInfo> entities,
            List<RelationInfo> relations
    ) {
        public record EntityInfo(String name,
                                 String type,
                                 String description,
                                 @Nullable String sourceChunkId) {
            public EntityInfo(String name, String type, String description) {
                this(name, type, description, null);
            }
        }

        public record RelationInfo(String sourceEntity, String targetEntity,
                                   String relationType, float strength,
                                   @Nullable String sourceChunkId) {
            public RelationInfo(String sourceEntity, String targetEntity,
                                String relationType, float strength) {
                this(sourceEntity, targetEntity, relationType, strength, null);
            }
        }
    }
}
