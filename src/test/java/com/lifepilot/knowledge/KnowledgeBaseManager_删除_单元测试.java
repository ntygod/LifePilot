package com.lifepilot.knowledge;

import com.lifepilot.knowledge.index.VectorIndexer;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * KnowledgeBaseManager 删除方法单元测试（Mock 依赖）。
 *
 * <p>验证 removeDocument 和 deleteKnowledgeBase 的调用时序、
 * VectorIndexer 为 null 时的降级行为、以及空知识库删除场景。
 *
 * @author zsg
 * @since 2026-03-11
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseManager_删除_单元测试 {

    @Mock KnowledgeBaseRepository kbRepository;
    @Mock DocumentRepository docRepository;
    @Mock DocumentChunkRepository chunkRepository;
    @Mock VectorIndexer vectorIndexer;

    KnowledgeBaseManager manager;
    private static final String KB_ID = "kb-001";
    private static final String DOC_ID_1 = "doc-001";
    private static final String DOC_ID_2 = "doc-002";

    @BeforeEach
    void setUp() {
        manager = new KnowledgeBaseManager(kbRepository, docRepository, chunkRepository, vectorIndexer);
    }

    // ---- 辅助方法 ----

    private Document 创建测试文档(String docId, String kbId) {
        return new Document(docId, kbId, "test.md", "/path/test.md", 1024,
                "text/markdown", "hash", DocumentStatus.READY, 2, 0,
                null, null, Map.of(), Instant.now(), Instant.now());
    }

    // ---- removeDocument 测试 ----

    @Test
    void removeDocument_向量索引在分块删除之前调用() {
        // 准备
        var doc = 创建测试文档(DOC_ID_1, KB_ID);
        when(docRepository.findById(DOC_ID_1)).thenReturn(Optional.of(doc));
        when(docRepository.findByKnowledgeBaseId(KB_ID)).thenReturn(List.of());

        // 执行
        manager.removeDocument(DOC_ID_1);

        // 验证调用时序：vectorIndexer.removeByDocumentId → chunkRepository.deleteByDocumentId
        InOrder inOrder = inOrder(vectorIndexer, chunkRepository);
        inOrder.verify(vectorIndexer).removeByDocumentId(DOC_ID_1);
        inOrder.verify(chunkRepository).deleteByDocumentId(DOC_ID_1);
    }

    @Test
    void removeDocument_VectorIndexer为null时正常执行() {
        // 构造 VectorIndexer 为 null 的 manager
        var managerNoVec = new KnowledgeBaseManager(kbRepository, docRepository, chunkRepository, null);
        var doc = 创建测试文档(DOC_ID_1, KB_ID);
        when(docRepository.findById(DOC_ID_1)).thenReturn(Optional.of(doc));
        when(docRepository.findByKnowledgeBaseId(KB_ID)).thenReturn(List.of());

        // 执行 — 不应抛异常
        assertThatCode(() -> managerNoVec.removeDocument(DOC_ID_1))
                .doesNotThrowAnyException();

        // 验证分块和文档仍然被删除
        verify(chunkRepository).deleteByDocumentId(DOC_ID_1);
        verify(docRepository).deleteById(DOC_ID_1);
        // vectorIndexer 为 null，不应有任何调用
        verifyNoInteractions(vectorIndexer);
    }

    // ---- deleteKnowledgeBase 测试 ----

    @Test
    void deleteKnowledgeBase_逐文档清理向量索引后删除知识库() {
        // 准备：知识库下有两个文档
        var doc1 = 创建测试文档(DOC_ID_1, KB_ID);
        var doc2 = 创建测试文档(DOC_ID_2, KB_ID);
        when(docRepository.findByKnowledgeBaseId(KB_ID)).thenReturn(List.of(doc1, doc2));

        // 执行
        manager.deleteKnowledgeBase(KB_ID);

        // 验证每个文档都调用了向量索引清理
        verify(vectorIndexer).removeByDocumentId(DOC_ID_1);
        verify(vectorIndexer).removeByDocumentId(DOC_ID_2);
        verify(kbRepository).deleteById(KB_ID);
    }

    @Test
    void deleteKnowledgeBase_索引清理在知识库删除之前执行() {
        // 准备
        var doc1 = 创建测试文档(DOC_ID_1, KB_ID);
        when(docRepository.findByKnowledgeBaseId(KB_ID)).thenReturn(List.of(doc1));

        // 执行
        manager.deleteKnowledgeBase(KB_ID);

        // 验证时序：索引清理 → kbRepository.deleteById
        InOrder inOrder = inOrder(vectorIndexer, kbRepository);
        inOrder.verify(vectorIndexer).removeByDocumentId(DOC_ID_1);
        inOrder.verify(kbRepository).deleteById(KB_ID);
    }

    @Test
    void deleteKnowledgeBase_VectorIndexer为null时正常执行() {
        // 构造 VectorIndexer 为 null 的 manager
        var managerNoVec = new KnowledgeBaseManager(kbRepository, docRepository, chunkRepository, null);
        var doc1 = 创建测试文档(DOC_ID_1, KB_ID);
        when(docRepository.findByKnowledgeBaseId(KB_ID)).thenReturn(List.of(doc1));

        // 执行 — 不应抛异常
        assertThatCode(() -> managerNoVec.deleteKnowledgeBase(KB_ID))
                .doesNotThrowAnyException();

        // 验证知识库仍然被删除
        verify(kbRepository).deleteById(KB_ID);
        verifyNoInteractions(vectorIndexer);
    }

    @Test
    void deleteKnowledgeBase_空知识库无文档时正常执行() {
        // 准备：知识库下无文档
        when(docRepository.findByKnowledgeBaseId(KB_ID)).thenReturn(List.of());

        // 执行 — 不应抛异常
        assertThatCode(() -> manager.deleteKnowledgeBase(KB_ID))
                .doesNotThrowAnyException();

        // 验证知识库仍然被删除
        verify(kbRepository).deleteById(KB_ID);
        // 无文档，不应调用索引清理
        verifyNoInteractions(vectorIndexer);
    }
}
