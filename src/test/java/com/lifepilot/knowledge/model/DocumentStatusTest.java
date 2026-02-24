package com.lifepilot.knowledge.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentStatus 枚举单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class DocumentStatusTest {

    // --- isTerminal() ---

    @Test
    void isTerminal_READY返回true() {
        assertTrue(DocumentStatus.READY.isTerminal());
    }

    @Test
    void isTerminal_ERROR返回true() {
        assertTrue(DocumentStatus.ERROR.isTerminal());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"UPLOADING", "PARSING", "CHUNKING",
            "INDEXING", "EXTRACTING", "UPDATING", "DELETING"})
    void isTerminal_非终态返回false(DocumentStatus status) {
        assertFalse(status.isTerminal());
    }

    // --- isRetryable() ---

    @Test
    void isRetryable_ERROR返回true() {
        assertTrue(DocumentStatus.ERROR.isRetryable());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"UPLOADING", "PARSING", "CHUNKING",
            "INDEXING", "EXTRACTING", "READY", "UPDATING", "DELETING"})
    void isRetryable_非ERROR返回false(DocumentStatus status) {
        assertFalse(status.isRetryable());
    }

    // --- displayName() ---

    @Test
    void displayName_返回正确的中文名称() {
        assertEquals("上传中", DocumentStatus.UPLOADING.displayName());
        assertEquals("解析中", DocumentStatus.PARSING.displayName());
        assertEquals("分块中", DocumentStatus.CHUNKING.displayName());
        assertEquals("索引中", DocumentStatus.INDEXING.displayName());
        assertEquals("提取中", DocumentStatus.EXTRACTING.displayName());
        assertEquals("就绪", DocumentStatus.READY.displayName());
        assertEquals("更新中", DocumentStatus.UPDATING.displayName());
        assertEquals("删除中", DocumentStatus.DELETING.displayName());
        assertEquals("错误", DocumentStatus.ERROR.displayName());
    }

    @Test
    void 枚举值数量为9() {
        assertEquals(9, DocumentStatus.values().length);
    }
}
