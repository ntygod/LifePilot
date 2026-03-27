package com.lifepilot.knowledge;

import com.lifepilot.knowledge.exception.DocumentNotFoundException;
import com.lifepilot.knowledge.exception.KnowledgeBaseNotFoundException;
import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseDatastoreRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库管理服务 — 提供知识库和文档的基础 CRUD 操作。
 *
 * <p>写操作标注 {@code @Transactional}，通过 {@code @Bean} 注册（不使用 {@code @Service}）。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class KnowledgeBaseManager {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseManager.class);

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository docRepository;
    private final DocumentChunkRepository chunkRepository;
    private final KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository;
    private final @Nullable VectorIndexer vectorIndexer;

    public KnowledgeBaseManager(KnowledgeBaseRepository kbRepository,
                                DocumentRepository docRepository,
                                DocumentChunkRepository chunkRepository,
                                KnowledgeBaseDatastoreRepository knowledgeBaseDatastoreRepository,
                                @Nullable VectorIndexer vectorIndexer) {
        this.kbRepository = kbRepository;
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.knowledgeBaseDatastoreRepository = knowledgeBaseDatastoreRepository;
        this.vectorIndexer = vectorIndexer;
    }

    /**
     * 创建新知识库。
     *
     * @param name             知识库名称
     * @param description      描述
     * @param embeddingModel   嵌入模型名称
     * @param rerankerModel    重排序模型（可选）
     * @param chunkingStrategy 分块策略（可选，默认 "smart"）
     * @param chunkingConfig   分块配置参数（可选）
     * @param tags             标签列表（可选）
     * @return 创建的知识库
     */
    @Transactional
    public KnowledgeBase createKnowledgeBase(String name, String description, @Nullable String embeddingModel,
                                             String rerankerModel, String chunkingStrategy,
                                             Map<String, Object> chunkingConfig, List<String> tags,
                                             @Nullable List<String> datastoreIds) {
        KnowledgeBase kb = KnowledgeBase.create(name, description, embeddingModel,
                rerankerModel, chunkingStrategy, chunkingConfig, tags);
        kb = new KnowledgeBase(
                kb.id(),
                kb.name(),
                kb.description(),
                kb.embeddingModel(),
                kb.rerankerModel(),
                kb.chunkingStrategy(),
                kb.chunkingConfig(),
                kb.documentCount(),
                kb.totalChunks(),
                kb.tags(),
                kb.createdAt(),
                kb.updatedAt(),
                false,
                null,
                kb.datastoreIds()
        );
        kbRepository.save(kb);
        knowledgeBaseDatastoreRepository.setAssociations(kb.id(), normalizeDatastoreIds(datastoreIds));
        log.info("知识库创建成功: id={}, name={}", kb.id(), kb.name());
        return enrichWithDatastoreIds(kb);
    }

    /**
     * 创建 Datastore 默认内部知识库。
     */
    @Transactional
    public KnowledgeBase createSystemManagedKnowledgeBase(String datastoreId,
                                                         String datastoreName,
                                                         @Nullable String embeddingModel,
                                                         @Nullable String rerankerModel) {
        KnowledgeBase kb = KnowledgeBase.createSystemManagedForDatastore(
                datastoreId,
                datastoreName,
                embeddingModel,
                rerankerModel
        );
        kbRepository.save(kb);
        knowledgeBaseDatastoreRepository.addAssociation(kb.id(), datastoreId);
        log.info("Datastore 默认知识库创建成功: datastoreId={}, knowledgeBaseId={}", datastoreId, kb.id());
        return enrichWithDatastoreIds(kb);
    }

    /**
     * 根据 id 获取知识库。
     *
     * @param id 知识库 id
     * @return 知识库 Optional
     */
    public Optional<KnowledgeBase> getKnowledgeBase(String id) {
        return kbRepository.findById(id).map(this::enrichWithDatastoreIds);
    }

    /**
     * 列出所有知识库（按 created_at 降序）。
     *
     * @return 知识库列表
     */
    public List<KnowledgeBase> listKnowledgeBases() {
        return kbRepository.findAll().stream()
                .map(this::enrichWithDatastoreIds)
                .toList();
    }

    /**
     * 根据条件查询知识库。
     *
     * @param q         关键词搜索（名称/描述）
     * @param tags      标签列表（多个标签，逗号分隔）
     * @param timeRange 时间范围（7d/30d）
     * @return 知识库列表
     */
    public List<KnowledgeBase> listKnowledgeBases(String q, String tags, String timeRange) {
        return kbRepository.findByConditions(q, tags, timeRange).stream()
                .map(this::enrichWithDatastoreIds)
                .toList();
    }

    /**
     * 更新知识库信息。null 参数表示不更新对应字段。
     *
     * @param id               知识库 id
     * @param name             新名称（null 不更新）
     * @param description      新描述（null 不更新）
     * @param embeddingModel   新嵌入模型（null 不更新）
     * @param rerankerModel    新重排序模型（null 不更新）
     * @param chunkingStrategy 新分块策略（null 不更新）
     * @param chunkingConfig   新分块配置（null 不更新）
     * @param tags             新标签列表（null 不更新）
     * @return 更新后的知识库
     * @throws KnowledgeBaseNotFoundException 知识库不存在时抛出
     */
    @Transactional
    public KnowledgeBase updateKnowledgeBase(String id, String name, String description,
                                             String embeddingModel, String rerankerModel,
                                             String chunkingStrategy, Map<String, Object> chunkingConfig,
                                             List<String> tags,
                                             @Nullable List<String> datastoreIds) {
        KnowledgeBase existing = kbRepository.findById(id)
                .orElseThrow(() -> new KnowledgeBaseNotFoundException("知识库不存在: id=" + id));
        KnowledgeBase hydratedExisting = enrichWithDatastoreIds(existing);

        KnowledgeBase updated = new KnowledgeBase(
                existing.id(),
                name != null ? name : existing.name(),
                description != null ? description : existing.description(),
                embeddingModel != null ? embeddingModel : existing.embeddingModel(),
                rerankerModel != null ? rerankerModel : existing.rerankerModel(),
                chunkingStrategy != null ? chunkingStrategy : existing.chunkingStrategy(),
                chunkingConfig != null ? chunkingConfig : existing.chunkingConfig(),
                existing.documentCount(),
                existing.totalChunks(),
                tags != null ? tags : hydratedExisting.tags(),
                existing.createdAt(),
                Instant.now(),
                hydratedExisting.systemManaged(),
                hydratedExisting.ownerDatastoreId(),
                datastoreIds != null ? List.copyOf(normalizeDatastoreIds(datastoreIds)) : hydratedExisting.datastoreIds()
        );
        kbRepository.save(updated);
        if (datastoreIds != null) {
            knowledgeBaseDatastoreRepository.setAssociations(id, normalizeDatastoreIds(datastoreIds));
        }
        log.info("知识库更新成功: id={}", id);
        return enrichWithDatastoreIds(updated);
    }

    /**
     * 删除知识库及其所有关联数据（文档、分块、向量索引）。
     *
     * <p>删除顺序：逐文档清理向量索引 → 删除知识库（CASCADE 删除文档和分块）。
     * vec0 虚拟表不支持 FK/触发器联动，因此向量索引仍需显式清理；
     * FTS5 由 {@code document_chunks} 的删除触发器自动维护，不再重复手工删除。
     *
     * @param id 知识库 id
     */
    @Transactional
    public void deleteKnowledgeBase(String id) {
        // 1. 查询该知识库下所有文档
        List<Document> docs = docRepository.findByKnowledgeBaseId(id);

        // 2. 逐文档清理向量索引（vec0 虚拟表无法通过 CASCADE 自动清理）
        if (vectorIndexer != null) {
            for (Document doc : docs) {
                vectorIndexer.removeByDocumentId(doc.id());
            }
        }

        // 3. 删除知识库（CASCADE 自动删除 documents 和 document_chunks，并由触发器清理 FTS5）
        kbRepository.deleteById(id);
        log.info("知识库删除成功: id={}", id);
    }

    /**
     * 列出指定文档的分块（支持 offset/limit 分页）。
     *
     * @param documentId 文档 id
     * @param offset     偏移量
     * @param limit      每页数量
     * @return 分块列表（按 chunkIndex 升序）
     */
    public List<com.lifepilot.knowledge.chunking.DocumentChunk> listDocumentChunks(String documentId,
                                                                                    int offset, int limit) {
        // 使用 chunkIndex 范围查询实现分页
        return chunkRepository.findByDocumentIdAndChunkIndexRange(documentId, offset, offset + limit - 1);
    }

    /**
     * 统计指定文档的分块数量。
     *
     * @param documentId 文档 id
     * @return 分块数量
     */
    public int countDocumentChunks(String documentId) {
        return chunkRepository.countByDocumentId(documentId);
    }

    /**
     * 列出指定知识库下的所有文档。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 文档列表
     */
    public List<Document> listDocuments(String knowledgeBaseId) {
        return docRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }

    /**
     * 删除文档及其关联分块，并更新知识库统计。
     *
     * <p>删除顺序：向量索引 → 分块（FTS5 触发器自动清理）→ 文档 → 刷新统计。
     * 向量索引必须在分块删除之前清理，因为 {@code removeByDocumentId} 依赖子查询 {@code document_chunks} 表。
     *
     * @param documentId 文档 id
     * @throws DocumentNotFoundException 文档不存在时抛出
     */
    @Transactional
    public void removeDocument(String documentId) {
        Document doc = docRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("文档不存在: id=" + documentId));

        String kbId = doc.knowledgeBaseId();

        // 1. 清理向量索引（在删除分块之前，因为 removeByDocumentId 依赖子查询 document_chunks）
        if (vectorIndexer != null) {
            vectorIndexer.removeByDocumentId(documentId);
        }

        // 2. 删除分块（FTS5 触发器自动清理 document_chunks_fts）
        chunkRepository.deleteByDocumentId(documentId);

        // 3. 删除文档
        docRepository.deleteById(documentId);

        // 4. 刷新知识库的文档数和分块数
        var remainingDocs = docRepository.findByKnowledgeBaseId(kbId);
        int docCount = remainingDocs.size();
        int totalChunks = remainingDocs.stream().mapToInt(Document::chunkCount).sum();
        kbRepository.updateDocumentCount(kbId, docCount, totalChunks);

        log.info("文档删除成功: id={}, 知识库统计已更新: kbId={}", documentId, kbId);
    }

    /**
     * 确保知识库已挂载指定 datastore。
     */
    @Transactional
    public void ensureDatastoreAssociation(String knowledgeBaseId, @Nullable String datastoreId) {
        if (datastoreId == null || datastoreId.isBlank()) {
            return;
        }
        kbRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new KnowledgeBaseNotFoundException("知识库不存在: id=" + knowledgeBaseId));
        knowledgeBaseDatastoreRepository.addAssociation(knowledgeBaseId, datastoreId.strip());
    }

    /**
     * 更新用户上传文档的 datastore 归属。
     *
     * <p>该操作会同时更新 documents 和 document_chunks 上的领域字段，
     * 让后续向量检索与 FTS 检索都按新的领域边界生效。</p>
     *
     * @param knowledgeBaseId 知识库 id
     * @param documentId      文档 id
     * @param datastoreId     新的 datastore 归属，可为空表示共享文档
     * @return 更新后的文档
     */
    @Transactional
    public Document updateDocumentDatastore(String knowledgeBaseId,
                                            String documentId,
                                            @Nullable String datastoreId) {
        kbRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new KnowledgeBaseNotFoundException("知识库不存在: id=" + knowledgeBaseId));

        Document existing = docRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("文档不存在: id=" + documentId));

        if (!knowledgeBaseId.equals(existing.knowledgeBaseId())) {
            throw new DocumentNotFoundException("文档不属于指定知识库: docId=" + documentId);
        }
        if (!existing.status().isTerminal()) {
            throw new IllegalArgumentException("文档处理中，暂不支持修改 Datastore 归属");
        }
        if (existing.sourceType() != DocumentSourceType.FILE) {
            throw new IllegalArgumentException("只有用户上传的文件文档支持修改 Datastore 归属");
        }

        String normalizedDatastoreId = datastoreId != null && !datastoreId.isBlank()
                ? datastoreId.strip()
                : null;

        if (normalizedDatastoreId != null) {
            ensureDatastoreAssociation(knowledgeBaseId, normalizedDatastoreId);
        }

        var sourceRef = new LinkedHashMap<String, Object>(existing.sourceRef());
        if (normalizedDatastoreId == null) {
            sourceRef.remove("datastoreId");
        } else {
            sourceRef.put("datastoreId", normalizedDatastoreId);
        }

        var updated = new Document(
                existing.id(),
                existing.knowledgeBaseId(),
                existing.fileName(),
                existing.filePath(),
                existing.fileSize(),
                existing.mimeType(),
                existing.contentHash(),
                existing.status(),
                existing.chunkCount(),
                existing.entityCount(),
                existing.errorMessage(),
                existing.lastProcessedStage(),
                existing.metadata(),
                existing.createdAt(),
                Instant.now(),
                existing.sourceType(),
                existing.sourceKey(),
                normalizedDatastoreId,
                existing.sourceCollectionId(),
                sourceRef
        );
        docRepository.save(updated);
        chunkRepository.updateSourceDatastoreIdByDocumentId(documentId, normalizedDatastoreId);
        log.info("文档 Datastore 归属已更新: kbId={}, docId={}, datastoreId={}",
                knowledgeBaseId, documentId, normalizedDatastoreId);
        return updated;
    }

    private KnowledgeBase enrichWithDatastoreIds(KnowledgeBase kb) {
        var datastoreIds = knowledgeBaseDatastoreRepository.findDatastoreIdsByKnowledgeBaseId(kb.id());
        return new KnowledgeBase(
                kb.id(),
                kb.name(),
                kb.description(),
                kb.embeddingModel(),
                kb.rerankerModel(),
                kb.chunkingStrategy(),
                kb.chunkingConfig(),
                kb.documentCount(),
                kb.totalChunks(),
                kb.tags(),
                kb.createdAt(),
                kb.updatedAt(),
                kb.systemManaged(),
                kb.ownerDatastoreId(),
                datastoreIds
        );
    }

    private LinkedHashSet<String> normalizeDatastoreIds(@Nullable List<String> datastoreIds) {
        var normalized = new LinkedHashSet<String>();
        if (datastoreIds == null) {
            return normalized;
        }
        for (String datastoreId : datastoreIds) {
            if (datastoreId != null && !datastoreId.isBlank()) {
                normalized.add(datastoreId.strip());
            }
        }
        return normalized;
    }
}
