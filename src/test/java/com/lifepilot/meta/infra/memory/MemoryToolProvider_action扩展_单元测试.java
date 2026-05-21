package com.lifepilot.meta.infra.memory;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryToolProvider complete / supersede / cancel 扩展能力单元测试。
 *
 * <p>核心目标：</p>
 * <ul>
 *   <li>complete 仅对 GOAL / PROJECT 有效，其余类型拒绝；</li>
 *   <li>cancel 单条模式（传 entityId）不再限类型，所有 ACTIVE 实体都可取消；</li>
 *   <li>supersede 同时写 succeeded_by 外键并转 SUPERSEDED 状态；</li>
 *   <li>非 ACTIVE / 实体缺失 / 参数缺失等边界路径返回 error。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("MemoryToolProvider action 扩展单元测试")
class MemoryToolProvider_action扩展_单元测试 {

    private HybridRetriever hybridRetriever;
    private SemanticMemory semanticMemory;
    private DynamicToolRegistry registry;

    @BeforeEach
    void 初始化() {
        hybridRetriever = mock(HybridRetriever.class);
        semanticMemory = mock(SemanticMemory.class);
        registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));

        var provider = new MemoryToolProvider(
                hybridRetriever,
                semanticMemory,
                mock(EpisodicMemory.class),
                mock(DocumentRetriever.class),
                mock(SessionKnowledgeBaseRepository.class),
                mock(SessionKnowledgeScopeResolver.class),
                new MemoryRetrievalProperties()
        );
        provider.registerTools(registry);
    }

    // ---------------- complete ----------------

    @Test
    void complete对ACTIVE状态的GOAL有效并转COMPLETED() {
        var goal = 构造实体("goal-1", EntityType.GOAL, "学 Rust", LifecycleState.ACTIVE);
        when(semanticMemory.findById("goal-1")).thenReturn(Optional.of(goal));

        var result = 执行工具("complete", Map.of("entityId", "goal-1"));

        assertThat(result.ok()).isTrue();
        assertThat(result.<String>getData("lifecycleState")).isEqualTo("COMPLETED");
        verify(semanticMemory).updateLifecycleState(
                eq("goal-1"), eq(LifecycleState.COMPLETED),
                eq("user-complete"), eq(ChangeSource.TOOL_EXPLICIT));
    }

    @Test
    void complete对ACTIVE状态的PROJECT有效() {
        var project = 构造实体("proj-1", EntityType.PROJECT, "知微 v1",
                LifecycleState.ACTIVE);
        when(semanticMemory.findById("proj-1")).thenReturn(Optional.of(project));

        var result = 执行工具("complete", Map.of("entityId", "proj-1"));

        assertThat(result.ok()).isTrue();
        verify(semanticMemory).updateLifecycleState(
                eq("proj-1"), eq(LifecycleState.COMPLETED),
                any(), eq(ChangeSource.TOOL_EXPLICIT));
    }

    @Test
    void complete对PREFERENCE应拒绝且不调用updateLifecycleState() {
        var pref = 构造实体("pref-1", EntityType.PREFERENCE, "深色主题",
                LifecycleState.ACTIVE);
        when(semanticMemory.findById("pref-1")).thenReturn(Optional.of(pref));

        var result = 执行工具("complete", Map.of("entityId", "pref-1"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("PREFERENCE").contains("complete");
        verify(semanticMemory, never()).updateLifecycleState(any(), any(), any(), any());
    }

    @Test
    void complete对已COMPLETED实体应拒绝() {
        var goal = 构造实体("goal-done", EntityType.GOAL, "已完成目标",
                LifecycleState.COMPLETED);
        when(semanticMemory.findById("goal-done")).thenReturn(Optional.of(goal));

        var result = 执行工具("complete", Map.of("entityId", "goal-done"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("ACTIVE");
        verify(semanticMemory, never()).updateLifecycleState(any(), any(), any(), any());
    }

    @Test
    void complete对不存在实体应返回错误() {
        when(semanticMemory.findById("ghost")).thenReturn(Optional.empty());

        var result = 执行工具("complete", Map.of("entityId", "ghost"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("不存在");
        verify(semanticMemory, never()).updateLifecycleState(any(), any(), any(), any());
    }

    // ---------------- cancel（单条模式）----------------

    @Test
    void cancel单条对PREFERENCE有效_扩覆盖所有类型() {
        var pref = 构造实体("pref-1", EntityType.PREFERENCE, "深色主题",
                LifecycleState.ACTIVE);
        when(semanticMemory.findById("pref-1")).thenReturn(Optional.of(pref));

        var result = 执行工具("cancel", Map.of("entityId", "pref-1"));

        assertThat(result.ok()).isTrue();
        assertThat(result.<String>getData("lifecycleState")).isEqualTo("CANCELLED");
        verify(semanticMemory).updateLifecycleState(
                eq("pref-1"), eq(LifecycleState.CANCELLED),
                eq("user-cancel"), eq(ChangeSource.TOOL_EXPLICIT));
    }

    @Test
    void cancel单条对EXPERIENCE有效() {
        var exp = 构造实体("exp-1", EntityType.EXPERIENCE, "SQLite 坑",
                LifecycleState.ACTIVE);
        when(semanticMemory.findById("exp-1")).thenReturn(Optional.of(exp));

        var result = 执行工具("cancel", Map.of("entityId", "exp-1"));

        assertThat(result.ok()).isTrue();
        verify(semanticMemory).updateLifecycleState(
                eq("exp-1"), eq(LifecycleState.CANCELLED),
                any(), eq(ChangeSource.TOOL_EXPLICIT));
    }

    @Test
    void cancel单条对GOAL有效() {
        var goal = 构造实体("goal-1", EntityType.GOAL, "学 Rust",
                LifecycleState.ACTIVE);
        when(semanticMemory.findById("goal-1")).thenReturn(Optional.of(goal));

        var result = 执行工具("cancel", Map.of("entityId", "goal-1"));

        assertThat(result.ok()).isTrue();
        verify(semanticMemory).updateLifecycleState(
                eq("goal-1"), eq(LifecycleState.CANCELLED),
                any(), eq(ChangeSource.TOOL_EXPLICIT));
    }

    @Test
    void cancel单条对非ACTIVE实体应拒绝() {
        var cancelled = 构造实体("already-cancelled", EntityType.GOAL,
                "已取消目标", LifecycleState.CANCELLED);
        when(semanticMemory.findById("already-cancelled"))
                .thenReturn(Optional.of(cancelled));

        var result = 执行工具("cancel", Map.of("entityId", "already-cancelled"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("ACTIVE");
        verify(semanticMemory, never()).updateLifecycleState(any(), any(), any(), any());
    }

    @Test
    void cancel单条对不存在实体应返回错误() {
        when(semanticMemory.findById("ghost")).thenReturn(Optional.empty());

        var result = 执行工具("cancel", Map.of("entityId", "ghost"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("不存在");
        verify(semanticMemory, never()).updateLifecycleState(any(), any(), any(), any());
    }

    // ---------------- supersede ----------------

    @Test
    void supersede应同时更新succeeded_by并转SUPERSEDED() {
        var oldEntity = 构造实体("goal-old", EntityType.GOAL, "旧目标",
                LifecycleState.ACTIVE);
        var newEntity = 构造实体("goal-new", EntityType.GOAL, "新目标",
                LifecycleState.ACTIVE);
        when(semanticMemory.findById("goal-old")).thenReturn(Optional.of(oldEntity));
        when(semanticMemory.findById("goal-new")).thenReturn(Optional.of(newEntity));

        var result = 执行工具("supersede", Map.of(
                "entityId", "goal-old",
                "new_entity_id", "goal-new"));

        assertThat(result.ok()).isTrue();
        assertThat(result.<String>getData("lifecycleState")).isEqualTo("SUPERSEDED");
        assertThat(result.<String>getData("succeededBy")).isEqualTo("goal-new");
        // succeeded_by 外键先写
        verify(semanticMemory).updateSucceededBy("goal-old", "goal-new");
        // 再转状态（事件走 updateLifecycleState 代发）
        verify(semanticMemory).updateLifecycleState(
                eq("goal-old"), eq(LifecycleState.SUPERSEDED),
                eq("user-supersede-by:goal-new"), eq(ChangeSource.TOOL_EXPLICIT));
    }

    @Test
    void supersede的new_entity_id不存在应报错且不写FK() {
        var oldEntity = 构造实体("goal-old", EntityType.GOAL, "旧目标",
                LifecycleState.ACTIVE);
        when(semanticMemory.findById("goal-old")).thenReturn(Optional.of(oldEntity));
        when(semanticMemory.findById("ghost-new")).thenReturn(Optional.empty());

        var result = 执行工具("supersede", Map.of(
                "entityId", "goal-old",
                "new_entity_id", "ghost-new"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("新实体不存在");
        verify(semanticMemory, never()).updateSucceededBy(any(), any());
        verify(semanticMemory, never()).updateLifecycleState(any(), any(), any(), any());
    }

    @Test
    void supersede的entityId不存在应报错() {
        when(semanticMemory.findById("ghost-old")).thenReturn(Optional.empty());

        var result = 执行工具("supersede", Map.of(
                "entityId", "ghost-old",
                "new_entity_id", "goal-new"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("被替代实体不存在");
        verify(semanticMemory, never()).updateSucceededBy(any(), any());
    }

    @Test
    void supersede同ID拒绝避免循环() {
        var result = 执行工具("supersede", Map.of(
                "entityId", "same",
                "new_entity_id", "same"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("不能");
        verify(semanticMemory, never()).findById(any());
    }

    @Test
    void supersede非ACTIVE的旧实体也应拒绝() {
        var archived = 构造实体("old-archived", EntityType.GOAL, "旧已归档",
                LifecycleState.ARCHIVED);
        var newEntity = 构造实体("new", EntityType.GOAL, "新",
                LifecycleState.ACTIVE);
        when(semanticMemory.findById("old-archived")).thenReturn(Optional.of(archived));
        when(semanticMemory.findById("new")).thenReturn(Optional.of(newEntity));

        var result = 执行工具("supersede", Map.of(
                "entityId", "old-archived",
                "new_entity_id", "new"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("ARCHIVED");
        verify(semanticMemory, never()).updateSucceededBy(any(), any());
    }

    @Test
    void tag应拒绝指向巩固画像实体() {
        var pref = 构造实体("pref-1", EntityType.PREFERENCE, "简洁回答",
                LifecycleState.ACTIVE);
        var profile = 构造实体("profile-1", EntityType.CUSTOM, "__consolidated_profile",
                LifecycleState.ACTIVE);
        when(semanticMemory.findById("pref-1")).thenReturn(Optional.of(pref));
        when(semanticMemory.findById("profile-1")).thenReturn(Optional.of(profile));

        var result = 执行工具("tag", Map.of(
                "sourceEntityId", "pref-1",
                "targetEntityId", "profile-1",
                "relationType", "conflicts_with"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("__consolidated_profile").contains("原子实体");
        verify(semanticMemory, never()).addRelation(any(), any());
    }

    // ---------------- 共享辅助 ----------------

    private com.lifepilot.tool.model.ToolResult 执行工具(String action, Map<String, Object> extra) {
        var tool = registry.resolve("memory").orElseThrow();
        var params = new HashMap<String, Object>();
        params.put("action", action);
        params.putAll(extra);
        return tool.execute(new ToolInput(
                tool.id(),
                Map.copyOf(params),
                tool.inputSchema(),
                null,
                Map.of("sessionId", "sess-test")));
    }

    private TemporalEntity 构造实体(String id, EntityType type, String name,
                                  LifecycleState state) {
        var now = Instant.now();
        return new TemporalEntity(
                id, type, name, "描述-" + name, Map.of(),
                1, true, now, null, "sess-test",
                0.8f, 0.5f, 0, null, now, now,
                state, null, null,
                com.lifepilot.memory.lifecycle.Temporality.PERSISTENT,
                null, false, List.of());
    }
}
