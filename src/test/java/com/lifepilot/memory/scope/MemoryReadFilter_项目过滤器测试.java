package com.lifepilot.memory.scope;

import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryScope;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void 五参重载_不限定scope时显式传空集合() {
        MemoryReadFilter f = MemoryReadFilter.buildForProject(
                null, "ms-personal", "ms-experience", false, Set.of());
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

    @Test
    void 读取过滤器不接受空白spaceId() {
        assertThatThrownBy(() -> MemoryReadFilter.of(Set.of("ms-personal", " "), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spaceId 不能为空");
    }

    @Test
    void 读取过滤器不接受null集合() {
        assertThatThrownBy(() -> new MemoryReadFilter(null, Set.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("记忆读取空间集合不能为空");
        assertThatThrownBy(() -> new MemoryReadFilter(Set.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("记忆读取 scope 集合不能为空");
    }

    @Test
    void 项目过滤器不接受空白主账户空间() {
        assertThatThrownBy(() -> MemoryReadFilter.buildForProject(
                null, " ", "ms-experience", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("personalSpaceId 不能为空");
    }

}
