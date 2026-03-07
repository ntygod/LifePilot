package com.lifepilot.knowledge.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KnowledgeBase record 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class KnowledgeBaseTest {

    @Test
    void create_生成非空UUID() {
        KnowledgeBase kb = KnowledgeBase.create("测试库", "描述", "text-embedding-3-small");

        assertNotNull(kb.id());
        assertFalse(kb.id().isBlank());
        // 验证是合法 UUID 格式
        assertDoesNotThrow(() -> UUID.fromString(kb.id()));
    }

    @Test
    void create_默认分块策略为smart() {
        KnowledgeBase kb = KnowledgeBase.create("测试库", "描述", "text-embedding-3-small");

        assertEquals("smart", kb.chunkingStrategy());
    }

    @Test
    void create_文档数和分块数初始化为零() {
        KnowledgeBase kb = KnowledgeBase.create("测试库", "描述", "text-embedding-3-small");

        assertEquals(0, kb.documentCount());
        assertEquals(0, kb.totalChunks());
    }

    @Test
    void create_rerankerModel为空() {
        KnowledgeBase kb = KnowledgeBase.create("测试库", "描述", "text-embedding-3-small");

        assertNull(kb.rerankerModel());
    }

    @Test
    void create_chunkingConfig为空Map() {
        KnowledgeBase kb = KnowledgeBase.create("测试库", "描述", "text-embedding-3-small");

        assertEquals(Map.of(), kb.chunkingConfig());
    }

    @Test
    void create_createdAt等于updatedAt() {
        KnowledgeBase kb = KnowledgeBase.create("测试库", "描述", "text-embedding-3-small");

        assertNotNull(kb.createdAt());
        assertNotNull(kb.updatedAt());
        assertEquals(kb.createdAt(), kb.updatedAt());
    }

    @Test
    void create_保留传入的名称和描述和模型() {
        KnowledgeBase kb = KnowledgeBase.create("我的知识库", "用于测试", "bge-large-zh");

        assertEquals("我的知识库", kb.name());
        assertEquals("用于测试", kb.description());
        assertEquals("bge-large-zh", kb.embeddingModel());
    }

    @Test
    void create_每次调用生成不同UUID() {
        KnowledgeBase kb1 = KnowledgeBase.create("库1", "描述", "model");
        KnowledgeBase kb2 = KnowledgeBase.create("库2", "描述", "model");

        assertNotEquals(kb1.id(), kb2.id());
    }
}
