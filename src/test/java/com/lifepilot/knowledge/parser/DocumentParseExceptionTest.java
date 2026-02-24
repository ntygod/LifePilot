package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.parser.DocumentParseException.Phase;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentParseException 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class DocumentParseExceptionTest {

    // --- 无 cause 构造器 ---

    @Test
    void 构造器_设置消息和阶段和路径() {
        var ex = new DocumentParseException("文件读取失败", Phase.FILE_READ, "/tmp/test.md");

        assertEquals("文件读取失败", ex.getMessage());
        assertEquals(Phase.FILE_READ, ex.getPhase());
        assertEquals("/tmp/test.md", ex.getFilePath());
        assertNull(ex.getCause());
    }

    // --- 带 cause 构造器 ---

    @Test
    void 构造器_带cause链() {
        var cause = new IOException("磁盘错误");
        var ex = new DocumentParseException("文件读取失败", Phase.FILE_READ, "/tmp/test.md", cause);

        assertEquals("文件读取失败", ex.getMessage());
        assertEquals(Phase.FILE_READ, ex.getPhase());
        assertEquals("/tmp/test.md", ex.getFilePath());
        assertSame(cause, ex.getCause());
    }

    // --- Phase 枚举 ---

    @Test
    void Phase枚举_包含四个值() {
        Phase[] phases = Phase.values();
        assertEquals(4, phases.length);
    }

    @Test
    void Phase枚举_valueOf正确解析() {
        assertEquals(Phase.FILE_READ, Phase.valueOf("FILE_READ"));
        assertEquals(Phase.FORMAT_DECODE, Phase.valueOf("FORMAT_DECODE"));
        assertEquals(Phase.TEXT_EXTRACTION, Phase.valueOf("TEXT_EXTRACTION"));
        assertEquals(Phase.METADATA_EXTRACTION, Phase.valueOf("METADATA_EXTRACTION"));
    }

    // --- 各阶段构造 ---

    @Test
    void 各阶段_均可正确构造() {
        for (Phase phase : Phase.values()) {
            var ex = new DocumentParseException("测试", phase, "/test");
            assertEquals(phase, ex.getPhase());
        }
    }

    // --- 继承关系 ---

    @Test
    void 继承RuntimeException() {
        var ex = new DocumentParseException("测试", Phase.FILE_READ, "/test");
        assertInstanceOf(RuntimeException.class, ex);
    }
}
