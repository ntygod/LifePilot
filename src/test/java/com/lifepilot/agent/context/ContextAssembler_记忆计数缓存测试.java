package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextAssembler 记忆统计缓存（metadataCache）按 filter 分桶测试。
 *
 * <p>回归修复：原 {@code volatile MetadataCache} 单字段按时间 TTL 命中 —
 * 主账户对话与隔离项目对话传入的 filter 不同，却共享同一缓存，
 * 导致"主账户先调用、5 分钟内项目对话读到主账户计数"的跨项目污染。</p>
 *
 * <p>修复后：缓存改为 {@code Map<MemoryReadFilter, MetadataCache>}，不同 filter 独立命中。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ContextAssembler_记忆计数缓存测试 {

    private SemanticMemory semanticMemory;
    private ContextAssembler assembler;

    @BeforeEach
    void 初始化() {
        semanticMemory = mock(SemanticMemory.class);
        var config = new AgentConfigProperties();
        config.getContext().setMaxContextTokens(4096);
        config.getContext().setOutputReservedTokens(512);
        var promptRegistry = mock(PromptRegistry.class);
        var memoryProps = new MemoryProperties();

        assembler = new ContextAssembler(
                config,
                promptRegistry,
                null,
                semanticMemory,
                memoryProps,
                null,
                null,
                null,
                null,
                null,
                null, null, null, null, null, null,
                null);
    }

    @Test
    void 不同filter独立命中缓存_不互相污染() {
        // 主账户 filter：只含 personal/experience 两个 space
        MemoryReadFilter mainFilter = MemoryReadFilter.buildForProject(
                null, "space-personal", "space-experience", false,
                Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
        // 隔离项目 filter：含三个 space
        MemoryReadFilter projectFilter = MemoryReadFilter.buildForProject(
                "space-proj", "space-personal", "space-experience", true,
                Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));

        when(semanticMemory.countByEntityType(eq(mainFilter)))
                .thenReturn(Map.of(EntityType.PREFERENCE, 15));
        when(semanticMemory.countByEntityType(eq(projectFilter)))
                .thenReturn(Map.of(EntityType.PREFERENCE, 3));

        var mainCounts = assembler.buildMemoryCounts(mainFilter);
        var projectCounts = assembler.buildMemoryCounts(projectFilter);

        assertThat(mainCounts.profileLine()).contains("15");
        assertThat(projectCounts.profileLine()).contains("3");
        // 底层调用次数：两个不同 filter 各走了一次（不复用彼此缓存）
        verify(semanticMemory, times(1)).countByEntityType(eq(mainFilter));
        verify(semanticMemory, times(1)).countByEntityType(eq(projectFilter));
    }

    @Test
    void 同一filter_TTL内二次调用走缓存() {
        MemoryReadFilter filter = MemoryReadFilter.buildForProject(
                null, "sp-personal", "sp-experience", false,
                Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
        when(semanticMemory.countByEntityType(eq(filter)))
                .thenReturn(Map.of(EntityType.PREFERENCE, 7));

        var first = assembler.buildMemoryCounts(filter);
        var second = assembler.buildMemoryCounts(filter);

        assertThat(first.profileLine()).contains("7");
        assertThat(second).isEqualTo(first);
        // 二次调用命中缓存，底层只调用一次
        verify(semanticMemory, times(1)).countByEntityType(eq(filter));
    }

    @Test
    void 先主账户后隔离项目_项目方不会拿到主账户的旧值() {
        MemoryReadFilter mainFilter = MemoryReadFilter.buildForProject(
                null, "sp-personal", "sp-experience", false,
                Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
        MemoryReadFilter isolatedFilter = MemoryReadFilter.buildForProject(
                "sp-proj", "sp-personal", "sp-experience", true,
                Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));

        when(semanticMemory.countByEntityType(eq(mainFilter)))
                .thenReturn(Map.of(EntityType.PREFERENCE, 20));
        when(semanticMemory.countByEntityType(eq(isolatedFilter)))
                .thenReturn(Map.of(EntityType.PREFERENCE, 1));

        // 主账户先填充缓存
        assembler.buildMemoryCounts(mainFilter);
        // 紧接着隔离项目调用 — 不能命中主账户缓存
        var projectCounts = assembler.buildMemoryCounts(isolatedFilter);

        assertThat(projectCounts.profileLine()).contains("1");
        assertThat(projectCounts.profileLine()).doesNotContain("20");
    }

    @Test
    void 空counts也按filter分别缓存_不返回他人结果() {
        MemoryReadFilter emptyFilter = MemoryReadFilter.buildForProject(
                "empty-proj", "p", "e", true,
                Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));
        MemoryReadFilter populatedFilter = MemoryReadFilter.buildForProject(
                null, "p", "e", false,
                Set.of(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT));

        when(semanticMemory.countByEntityType(eq(emptyFilter))).thenReturn(Map.of());
        when(semanticMemory.countByEntityType(eq(populatedFilter)))
                .thenReturn(Map.of(EntityType.PREFERENCE, 5));

        var emptyCounts = assembler.buildMemoryCounts(emptyFilter);
        var populatedCounts = assembler.buildMemoryCounts(populatedFilter);

        assertThat(emptyCounts).isEqualTo(ContextAssembler.MemoryCounts.EMPTY);
        assertThat(populatedCounts.profileLine()).contains("5");
    }
}
