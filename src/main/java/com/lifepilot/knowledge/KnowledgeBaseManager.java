package com.lifepilot.knowledge;

import com.lifepilot.knowledge.exception.DocumentNotFoundException;
import com.lifepilot.knowledge.exception.KnowledgeBaseNotFoundException;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

    public KnowledgeBaseManager(KnowledgeBaseRepository kbRepository,
                                DocumentRepository docRepository,
                                DocumentChunkRepository chunkRepository) {
        this.kbRepository = kbRepository;
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
    }

    /**
     * 创建新知识库。
     *
     * @param name           知识库名称
     * @param description    描述
     * @param embeddingModel 嵌入模型名称
     * @return 创建的知识库
     */
    @Transactional
    public KnowledgeBase createKnowledgeBase(String name, String description, String embeddingModel) {
        KnowledgeBase kb = KnowledgeBase.create(name, description, embeddingModel);
        kbRepository.save(kb);
        log.info("知识库创建成功: id={}, name={}", kb.id(), kb.name());
        return kb;
    }

    /**
     * 根据 id 获取知识库。
     *
     * @param id 知识库 id
     * @return 知识库 Optional
     */
    public Optional<KnowledgeBase> getKnowledgeBase(String id) {
        return kbRepository.findById(id);
    }

    /**
     * 列出所有知识库（按 created_at 降序）。
     *
     * @return 知识库列表
     */
    public List<KnowledgeBase> listKnowledgeBases() {
        return kbRepository.findAll();
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
        return kbRepository.findByConditions(q, tags, timeRange);
    }

    /**
     * 更新知识库信息。null 参数表示不更新对应字段。
     *
     * @param id          知识库 id
     * @param name        新名称（null 不更新）
     * @param description 新描述（null 不更新）
     * @return 更新后的知识库
     * @throws KnowledgeBaseNotFoundException 知识库不存在时抛出
     */
    @Transactional
    public KnowledgeBase updateKnowledgeBase(String id, String name, String description) {
        return updateKnowledgeBase(id, name, description, null);
    }

    /**
     * 更新知识库信息。null 参数表示不更新对应字段。
     *
     * @param id          知识库 id
     * @param name        新名称（null 不更新）
     * @param description 新描述（null 不更新）
     * @param tags        新标签列表（null 不更新）
     * @return 更新后的知识库
     * @throws KnowledgeBaseNotFoundException 知识库不存在时抛出
     */
    @Transactional
    public KnowledgeBase updateKnowledgeBase(String id, String name, String description, 
                                            List<String> tags) {
        KnowledgeBase existing = kbRepository.findById(id)
                .orElseThrow(() -> new KnowledgeBaseNotFoundException("知识库不存在: id=" + id));

        KnowledgeBase updated = new KnowledgeBase(
                existing.id(),
                name != null ? name : existing.name(),
                description != null ? description : existing.description(),
                existing.embeddingModel(),
                existing.rerankerModel(),
                existing.chunkingStrategy(),
                existing.chunkingConfig(),
                existing.documentCount(),
                existing.totalChunks(),
                tags != null ? tags : existing.tags(),
                existing.createdAt(),
                Instant.now()
        );
        kbRepository.save(updated);
        log.info("知识库更新成功: id={}, description={}, tags={}", id, 
                description != null ? "已更新" : "未更新",
                tags != null ? "已更新" : "未更新");
        return updated;
    }

    /**
     * 删除知识库（级联删除文档和分块，依赖数据库 ON DELETE CASCADE）。
     *
     * @param id 知识库 id
     */
    @Transactional
    public void deleteKnowledgeBase(String id) {
        kbRepository.deleteById(id);
        log.info("知识库删除成功: id={}", id);
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
     * @param documentId 文档 id
     * @throws DocumentNotFoundException 文档不存在时抛出
     */
    @Transactional
    public void removeDocument(String documentId) {
        Document doc = docRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("文档不存在: id=" + documentId));

        String kbId = doc.knowledgeBaseId();
        chunkRepository.deleteByDocumentId(documentId);
        docRepository.deleteById(documentId);

        // 刷新知识库的文档数和分块数
        var remainingDocs = docRepository.findByKnowledgeBaseId(kbId);
        int docCount = remainingDocs.size();
        int totalChunks = remainingDocs.stream().mapToInt(Document::chunkCount).sum();
        kbRepository.updateDocumentCount(kbId, docCount, totalChunks);

        log.info("文档删除成功: id={}, 知识库统计已更新: kbId={}", documentId, kbId);
    }
}
