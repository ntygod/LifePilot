package com.lifepilot.memory.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MemoryReadFilter 项目上下文工厂方法测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
class MemoryReadFilter_项目过滤器测试 {

    @Test
    void 隔离项目_包含项目space_和主账户两个space() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                "ms-project", "ms-personal", "ms-experience", true);
        assertTrue(f.spaceIds().contains("ms-project"));
        assertTrue(f.spaceIds().contains("ms-personal"));
        assertTrue(f.spaceIds().contains("ms-experience"));
        assertEquals(3, f.spaceIds().size());
    }

    @Test
    void 不隔离项目_只看主账户两个space() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                "ms-project", "ms-personal", "ms-experience", false);
        assertFalse(f.spaceIds().contains("ms-project"));
        assertTrue(f.spaceIds().contains("ms-personal"));
        assertTrue(f.spaceIds().contains("ms-experience"));
        assertEquals(2, f.spaceIds().size());
    }

    @Test
    void 主账户对话_projectSpaceId为null_忽略项目space() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                null, "ms-personal", "ms-experience", false);
        assertEquals(2, f.spaceIds().size());
        assertTrue(f.spaceIds().contains("ms-personal"));
        assertTrue(f.spaceIds().contains("ms-experience"));
    }

    @Test
    void 主账户对话_即便isolated为true也忽略null项目space() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                null, "ms-personal", "ms-experience", true);
        assertEquals(2, f.spaceIds().size());
    }
}
