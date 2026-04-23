package com.lifepilot.memory.scope;

import org.junit.jupiter.api.Test;

import java.util.Set;

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

    @Test
    void 五参重载_限定scopes_且保留spaces() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                "ms-project", "ms-personal", "ms-experience", true,
                Set.of(MemoryScope.USER_PROFILE));
        assertEquals(3, f.spaceIds().size());
        assertTrue(f.spaceIds().contains("ms-project"));
        assertTrue(f.restrictsScopes());
        assertEquals(Set.of(MemoryScope.USER_PROFILE), f.scopes());
    }

    @Test
    void 五参重载_scopes传null等同不限定scope() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                null, "ms-personal", "ms-experience", false, null);
        assertEquals(2, f.spaceIds().size());
        assertFalse(f.restrictsScopes());
    }

    @Test
    void 五参重载_多scope组合正确() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                null, "ms-personal", "ms-experience", false,
                Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
        assertEquals(Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT), f.scopes());
        assertEquals(2, f.spaceIds().size());
    }

    // ==================== fromProjectContextOrFallback ====================

    @Test
    void fallback_ctx不存在_空scope_返回all() {
        MemoryReadFilter f = MemoryReadFilter.fromProjectContextOrFallback(
                false, null, null, null, false, Set.of());
        assertTrue(f.isUnrestricted());
    }

    @Test
    void fallback_ctx不存在_null_scope_返回all() {
        MemoryReadFilter f = MemoryReadFilter.fromProjectContextOrFallback(
                false, null, null, null, false, null);
        assertTrue(f.isUnrestricted());
    }

    @Test
    void fallback_ctx不存在_AGENT_EXPERIENCE_scope_等同agentExperience() {
        MemoryReadFilter f = MemoryReadFilter.fromProjectContextOrFallback(
                false, null, null, null, false, Set.of(MemoryScope.AGENT_EXPERIENCE));
        assertEquals(MemoryReadFilter.agentExperience(), f);
        assertEquals(Set.of(MemoryScope.AGENT_EXPERIENCE), f.scopes());
        assertFalse(f.restrictsSpaces());
    }

    @Test
    void fallback_ctx不存在_USER_PROFILE_scope_等同userProfile() {
        MemoryReadFilter f = MemoryReadFilter.fromProjectContextOrFallback(
                false, null, null, null, false, Set.of(MemoryScope.USER_PROFILE));
        assertEquals(MemoryReadFilter.userProfile(), f);
    }

    @Test
    void fallback_ctx不存在_USER_PROFILE和USER_FACT_等同userMemory() {
        MemoryReadFilter f = MemoryReadFilter.fromProjectContextOrFallback(
                false, null, null, null, false,
                Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
        assertEquals(MemoryReadFilter.userMemory(), f);
    }

    @Test
    void fallback_ctx不存在_其他scope组合_仅限scope空spaces() {
        MemoryReadFilter f = MemoryReadFilter.fromProjectContextOrFallback(
                false, null, null, null, false, Set.of(MemoryScope.USER_FACT));
        assertEquals(Set.of(MemoryScope.USER_FACT), f.scopes());
        assertFalse(f.restrictsSpaces());
    }

    @Test
    void fromProjectContext_ctx存在_隔离项目_含三space并限定scope() {
        MemoryReadFilter f = MemoryReadFilter.fromProjectContextOrFallback(
                true, "sp-proj", "sp-personal", "sp-experience", true,
                Set.of(MemoryScope.USER_PROFILE));
        assertEquals(3, f.spaceIds().size());
        assertTrue(f.spaceIds().contains("sp-proj"));
        assertTrue(f.spaceIds().contains("sp-personal"));
        assertTrue(f.spaceIds().contains("sp-experience"));
        assertEquals(Set.of(MemoryScope.USER_PROFILE), f.scopes());
    }

    @Test
    void fromProjectContext_ctx存在_主账户对话_忽略null项目space() {
        MemoryReadFilter f = MemoryReadFilter.fromProjectContextOrFallback(
                true, null, "sp-personal", "sp-experience", false,
                Set.of(MemoryScope.AGENT_EXPERIENCE));
        assertEquals(2, f.spaceIds().size());
        assertTrue(f.spaceIds().contains("sp-personal"));
        assertTrue(f.spaceIds().contains("sp-experience"));
        assertEquals(Set.of(MemoryScope.AGENT_EXPERIENCE), f.scopes());
    }
}
