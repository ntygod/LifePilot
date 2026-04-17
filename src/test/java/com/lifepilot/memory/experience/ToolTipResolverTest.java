package com.lifepilot.memory.experience;

import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolTipResolver 单元测试。
 *
 * @author zsg
 * @since 2026-04-17
 */
@ExtendWith(MockitoExtension.class)
class ToolTipResolverTest {

    @Test
    void semanticMemory为null时返回空串() {
        var resolver = new ToolTipResolver(null);
        assertThat(resolver.tipsFor("file.read")).isEmpty();
    }

    @Test
    void toolId为null或空白时返回空串() {
        var semanticMemory = mock(SemanticMemory.class);
        var resolver = new ToolTipResolver(semanticMemory);

        assertThat(resolver.tipsFor(null)).isEmpty();
        assertThat(resolver.tipsFor("")).isEmpty();
        assertThat(resolver.tipsFor("  ")).isEmpty();
        verify(semanticMemory, never()).findCurrentByType(any(), any());
    }

    @Test
    void 无匹配经验时返回空串() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of());

        var resolver = new ToolTipResolver(semanticMemory);
        assertThat(resolver.tipsFor("file.read")).isEmpty();
    }

    @Test
    void 匹配TOOL_LEVEL经验时返回历史经验提示前缀() {
        var semanticMemory = mock(SemanticMemory.class);
        var exp = experience("file.read",
                SubtaskReflector.TOOL_LEVEL,
                List.of("注意 skill 参数不要带空格"),
                0.9f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(exp));

        var resolver = new ToolTipResolver(semanticMemory);
        String tips = resolver.tipsFor("file.read");

        assertThat(tips)
                .startsWith("[历史经验提示]")
                .contains("注意 skill 参数不要带空格");
    }

    @Test
    void 不同toolId的经验不会交叉命中() {
        var semanticMemory = mock(SemanticMemory.class);
        var other = experience("web.search",
                SubtaskReflector.TOOL_LEVEL,
                List.of("搜索结果可能过期"),
                0.8f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(other));

        var resolver = new ToolTipResolver(semanticMemory);
        assertThat(resolver.tipsFor("file.read")).isEmpty();
    }

    @Test
    void 非TOOL_LEVEL的经验被过滤掉() {
        var semanticMemory = mock(SemanticMemory.class);
        var taskLevel = experience("file.read",
                "TASK_LEVEL",
                List.of("任务级经验"),
                0.9f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(taskLevel));

        var resolver = new ToolTipResolver(semanticMemory);
        assertThat(resolver.tipsFor("file.read")).isEmpty();
    }

    @Test
    void 缓存在TTL内只查询一次() {
        var semanticMemory = mock(SemanticMemory.class);
        var exp = experience("file.read",
                SubtaskReflector.TOOL_LEVEL,
                List.of("教训 A"),
                0.9f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(exp));

        var resolver = new ToolTipResolver(semanticMemory);
        resolver.tipsFor("file.read");
        resolver.tipsFor("file.read");
        resolver.tipsFor("file.read");

        // 同一 toolId 三次查询只应触发一次 SemanticMemory 读取
        verify(semanticMemory, org.mockito.Mockito.times(1))
                .findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class));
    }

    @Test
    void 多条匹配经验按importanceScore降序取top2() {
        var semanticMemory = mock(SemanticMemory.class);
        var low = experience("file.read", SubtaskReflector.TOOL_LEVEL, List.of("低优"), 0.1f);
        var high = experience("file.read", SubtaskReflector.TOOL_LEVEL, List.of("最高优"), 0.95f);
        var mid = experience("file.read", SubtaskReflector.TOOL_LEVEL, List.of("次优"), 0.7f);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenReturn(List.of(low, high, mid));

        var resolver = new ToolTipResolver(semanticMemory);
        String tips = resolver.tipsFor("file.read");

        assertThat(tips)
                .contains("最高优")
                .contains("次优")
                .doesNotContain("低优");
    }

    @Test
    void 查询抛异常时降级返回空串() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.findCurrentByType(eq(EntityType.EXPERIENCE), any(MemoryReadFilter.class)))
                .thenThrow(new RuntimeException("DB down"));

        var resolver = new ToolTipResolver(semanticMemory);
        assertThat(resolver.tipsFor("file.read")).isEmpty();
    }

    // ==================== helper ====================

    private static TemporalEntity experience(String toolId,
                                             String granularity,
                                             List<String> lessons,
                                             float importance) {
        Instant now = Instant.now();
        return new TemporalEntity(
                "exp-" + toolId + "-" + granularity,
                EntityType.EXPERIENCE,
                "experience for " + toolId,
                null,
                Map.of("toolId", toolId, "granularity", granularity, "lessons", lessons),
                1,
                true,
                now,
                null,
                null,
                1.0f,
                importance,
                0,
                null,
                now,
                now);
    }
}
