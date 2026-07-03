package com.lifepilot.agent.learning.conflict;

import com.lifepilot.agent.learning.conflict.ConflictResolutionRepository;
import com.lifepilot.agent.learning.conflict.ConflictResolutionService;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.ConflictVerdict;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConflictResolutionService LLM 冲突裁决单元测试 — 覆盖高相似过滤、入队、调 LLM
 * 解析 verdict、按 verdict 应用状态转换的完整路径，以及失败暴露与非法 target 校验。
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConflictResolutionService LLM 冲突裁决")
class ConflictResolutionService_单元测试 {

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private PromptRegistry promptRegistry;

    @Mock
    private VectorSearcher vectorSearcher;

    @Mock
    private ConflictResolutionRepository queueRepository;

    @Mock
    private SemanticMemory semanticMemory;

    private ConflictResolutionService service;

    private static final String NEW_ID = "new-1";
    private static final String TARGET_ID = "old-1";
    private static final String QUEUE_ID = "queue-1";

    @BeforeEach
    void 初始化() {
        service = new ConflictResolutionService(
                generationRouter, promptRegistry, vectorSearcher,
                queueRepository, semanticMemory);
        // 默认渲染提示词返回占位文本，具体内容不在本测试关注范围
        lenient().when(promptRegistry.render(anyString(), any())).thenReturn("prompt-body");
    }

    /** 构造指定 id / 生命周期状态的实体，其他字段使用测试默认值。 */
    private static TemporalEntity buildEntity(String id, LifecycleState state) {
        var now = Instant.now();
        return new TemporalEntity(
                id, EntityType.PREFERENCE, "name-" + id, "desc-" + id,
                Map.of(), 1, true, now, null, null,
                0.9f, 0.5f, 0, null, now, now,
                state, null, null, Temporality.PERSISTENT,
                null, false, List.of(),
                        com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                        com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                        1.0f,
                        1,
                        now);
    }

    /** 构造裁决前的新实体（ACTIVE）。 */
    private static TemporalEntity newEntity() {
        return buildEntity(NEW_ID, LifecycleState.ACTIVE);
    }

    /** 构造 LLM 响应并 mock GenerationRouter 返回之。 */
    private void 模拟LLM返回(String content) {
        var response = new LlmResponse(content, null, null, List.of(), Map.of(), 100, 50, null, 0, "test-provider", "test-model", 200L, false);
        when(generationRouter.call(
                anyString(), anyString(), any(), any(), any(),
                any(GenerationCapability.class), any()))
                .thenReturn(response);
    }

    /** 模拟 VectorSearcher 返回 old 实体相似度 ≥ 0.9。 */
    private void 模拟高相似度(String entityId, float similarity) {
        when(vectorSearcher.searchEntities(anyString(), eq(10), eq(0.0f)))
                .thenReturn(List.of(new VectorSearchResult(entityId, similarity)));
    }

    // ------------------------------------------------------------------
    // 场景 1：REPLACE verdict
    // ------------------------------------------------------------------

    @Test
    @DisplayName("高相似度命中应入队_调LLM_应用REPLACE转SUPERSEDED")
    void 高相似度命中应入队_调LLM_应用REPLACE() {
        // given
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        模拟LLM返回("""
                {"verdict":"REPLACE","target_id":"old-1","rationale":"新记忆否定旧偏好"}
                """);
        when(semanticMemory.findById(TARGET_ID)).thenReturn(Optional.of(oldEnt));

        // when
        service.resolveSync(newEnt, List.of(oldEnt));

        // then
        verify(queueRepository).enqueue(eq(NEW_ID), eq(List.of(TARGET_ID)));
        verify(generationRouter).call(
                anyString(), anyString(), any(), any(), any(),
                eq(GenerationCapability.CHAT), any());
        verify(semanticMemory).updateLifecycleState(
                eq(TARGET_ID),
                eq(LifecycleState.SUPERSEDED),
                eq("replaced-by:" + NEW_ID),
                eq(ChangeSource.CONFLICT_RESOLVE));
        verify(queueRepository).markResolved(eq(QUEUE_ID), any(ConflictVerdict.class));
        verify(queueRepository, never()).markFailed(anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // 场景 2：TIMELINE verdict — 先 updateSucceededBy 再 updateLifecycleState
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TIMELINE应先updateSucceededBy再updateLifecycleState")
    void TIMELINE应先updateSucceededBy再updateLifecycleState() {
        // given
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.88f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        模拟LLM返回("""
                {"verdict":"TIMELINE","target_id":"old-1","rationale":"状态演化"}
                """);
        when(semanticMemory.findById(TARGET_ID)).thenReturn(Optional.of(oldEnt));

        // when
        service.resolveSync(newEnt, List.of(oldEnt));

        // then — 顺序验证
        InOrder io = inOrder(semanticMemory);
        io.verify(semanticMemory).findById(TARGET_ID);
        io.verify(semanticMemory).updateSucceededBy(TARGET_ID, NEW_ID);
        io.verify(semanticMemory).updateLifecycleState(
                eq(TARGET_ID),
                eq(LifecycleState.SUPERSEDED),
                eq("succeeded-by:" + NEW_ID),
                eq(ChangeSource.CONFLICT_RESOLVE));
        verify(queueRepository).markResolved(eq(QUEUE_ID), any(ConflictVerdict.class));
    }

    // ------------------------------------------------------------------
    // 场景 3：COEXIST verdict — 不触发状态变化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("COEXIST不触发状态变化_仅markResolved")
    void COEXIST不触发状态变化() {
        // given
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.87f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        模拟LLM返回("""
                {"verdict":"COEXIST","target_id":"","rationale":"二者并列"}
                """);

        // when
        service.resolveSync(newEnt, List.of(oldEnt));

        // then — 无 updateLifecycleState / updateSucceededBy 调用
        verify(semanticMemory, never()).updateLifecycleState(
                anyString(), any(LifecycleState.class), anyString(), any(ChangeSource.class));
        verify(semanticMemory, never()).updateSucceededBy(anyString(), anyString());
        verify(queueRepository).markResolved(eq(QUEUE_ID), any(ConflictVerdict.class));
    }

    // ------------------------------------------------------------------
    // 场景 4：低相似度候选应被过滤 — 不入队也不调 LLM
    // ------------------------------------------------------------------

    @Test
    @DisplayName("低相似度候选应被过滤_不入队")
    void 低相似度候选应被过滤_不入队() {
        // given — 候选相似度仅 0.5 < 阈值 0.85
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.5f);

        // when
        service.resolveSync(newEnt, List.of(oldEnt));

        // then — 相似度过滤在入队前，所以 queue / LLM / status 均无调用
        verify(queueRepository, never()).enqueue(anyString(), any());
        verify(generationRouter, never()).call(
                anyString(), anyString(), any(), any(), any(),
                any(GenerationCapability.class), any());
        verify(semanticMemory, never()).updateLifecycleState(
                anyString(), any(LifecycleState.class), anyString(), any(ChangeSource.class));
    }

    @Test
    @DisplayName("相似度查询失败应抛出且不入队")
    void 相似度查询失败应抛出且不入队() {
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        when(vectorSearcher.searchEntities(anyString(), eq(10), eq(0.0f)))
                .thenThrow(new RuntimeException("向量索引不可用"));

        assertThrows(RuntimeException.class, () -> service.resolveSync(newEnt, List.of(oldEnt)));

        verify(queueRepository, never()).enqueue(anyString(), any());
        verify(generationRouter, never()).call(
                anyString(), anyString(), any(), any(), any(),
                any(GenerationCapability.class), any());
    }

    @Test
    @DisplayName("相似度查询返回null候选应抛出且不入队")
    void 相似度查询返回null候选应抛出且不入队() {
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        when(vectorSearcher.searchEntities(anyString(), eq(10), eq(0.0f)))
                .thenReturn(java.util.Collections.singletonList(null));

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));

        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决相似度查询结果不能包含 null 元素");
        verify(queueRepository, never()).enqueue(anyString(), any());
        verify(generationRouter, never()).call(
                anyString(), anyString(), any(), any(), any(),
                any(GenerationCapability.class), any());
    }

    // ------------------------------------------------------------------
    // 场景 5：LLM 调用失败应 markFailed 并抛出
    // ------------------------------------------------------------------

    @Test
    @DisplayName("LLM调用失败应markFailed并抛出")
    void LLM调用失败应markFailed并抛出() {
        // given
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        when(generationRouter.call(
                anyString(), anyString(), any(), any(), any(),
                any(GenerationCapability.class), any()))
                .thenThrow(new RuntimeException("模拟 LLM 超时"));

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));
        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 LLM 调用失败");

        verify(queueRepository).enqueue(eq(NEW_ID), any());
        verify(queueRepository).markFailed(eq(QUEUE_ID), anyString());
        verify(queueRepository, never()).markResolved(anyString(), any());
        verify(semanticMemory, never()).updateLifecycleState(
                anyString(), any(LifecycleState.class), anyString(), any(ChangeSource.class));
    }

    @Test
    @DisplayName("prompt渲染为空应markFailed并抛出")
    void prompt渲染为空应markFailed并抛出() {
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        when(promptRegistry.render(anyString(), any())).thenReturn(" ");

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));

        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 LLM 调用失败")
                .hasCauseInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThat(ex.getCause())
                .hasMessageContaining("冲突裁决 prompt 渲染结果不能为空");
        verify(queueRepository).markFailed(eq(QUEUE_ID), anyString());
        verify(generationRouter, never()).call(
                anyString(), anyString(), any(), any(), any(),
                any(GenerationCapability.class), any());
    }

    @Test
    @DisplayName("LLM响应为空应markFailed并抛出")
    void LLM响应为空应markFailed并抛出() {
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        when(generationRouter.call(
                anyString(), anyString(), any(), any(), any(),
                any(GenerationCapability.class), any()))
                .thenReturn(null);

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));

        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 LLM 调用失败")
                .hasCauseInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThat(ex.getCause())
                .hasMessageContaining("冲突裁决 LLM 响应不能为空");
        verify(queueRepository).markFailed(eq(QUEUE_ID), anyString());
        verify(queueRepository, never()).markResolved(anyString(), any());
    }

    @Test
    @DisplayName("LLM返回非候选targetId应markFailed并抛出")
    void LLM返回非候选targetId应markFailed并抛出() {
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        模拟LLM返回("""
                {"verdict":"REPLACE","target_id":"other-1","rationale":"覆盖其他实体"}
                """);

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));

        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 LLM 调用失败")
                .hasCauseInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThat(ex.getCause())
                .hasMessageContaining("冲突裁决 target_id 不在候选集中");
        verify(queueRepository).markFailed(eq(QUEUE_ID), anyString());
        verify(queueRepository, never()).markResolved(anyString(), any());
        verify(semanticMemory, never()).findById("other-1");
        verify(semanticMemory, never()).updateLifecycleState(
                anyString(), any(LifecycleState.class), anyString(), any(ChangeSource.class));
    }

    @Test
    @DisplayName("LLM失败且markFailed失败时应保留原始异常并追加suppressed")
    void LLM失败且markFailed失败时应保留原始异常并追加suppressed() {
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        when(generationRouter.call(
                anyString(), anyString(), any(), any(), any(),
                any(GenerationCapability.class), any()))
                .thenThrow(new RuntimeException("模拟 LLM 超时"));
        doThrow(new IllegalStateException("队列失败标记写入失败"))
                .when(queueRepository).markFailed(eq(QUEUE_ID), anyString());

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));

        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 LLM 调用失败")
                .hasCauseInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThat(ex.getCause())
                .hasMessageContaining("模拟 LLM 超时");
        org.assertj.core.api.Assertions.assertThat(ex.getSuppressed())
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(ex.getSuppressed()[0])
                .hasMessageContaining("队列失败标记写入失败");
        verify(queueRepository, never()).markResolved(anyString(), any());
    }

    // ------------------------------------------------------------------
    // 场景 6：target 已非 ACTIVE 应 markFailed 并抛出
    // ------------------------------------------------------------------

    @Test
    @DisplayName("target已非ACTIVE应markFailed并抛出")
    void target已非ACTIVE应markFailed并抛出() {
        // given — REPLACE verdict 但 target 处于 SUPERSEDED
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        模拟LLM返回("""
                {"verdict":"REPLACE","target_id":"old-1","rationale":"覆盖旧偏好"}
                """);
        // findById 返回一个已处于 SUPERSEDED 的实体，验证 apply 路径跳过
        var supersededTarget = buildEntity(TARGET_ID, LifecycleState.SUPERSEDED);
        when(semanticMemory.findById(TARGET_ID)).thenReturn(Optional.of(supersededTarget));

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));
        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 verdict 应用失败");

        verify(semanticMemory, never()).updateLifecycleState(
                anyString(), any(LifecycleState.class), anyString(), any(ChangeSource.class));
        verify(queueRepository).markFailed(eq(QUEUE_ID), anyString());
        verify(queueRepository, never()).markResolved(anyString(), any());
    }

    @Test
    @DisplayName("verdict应用失败且markFailed失败时应保留原始异常并追加suppressed")
    void verdict应用失败且markFailed失败时应保留原始异常并追加suppressed() {
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        模拟LLM返回("""
                {"verdict":"REPLACE","target_id":"old-1","rationale":"覆盖旧偏好"}
                """);
        when(semanticMemory.findById(TARGET_ID)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("队列失败标记写入失败"))
                .when(queueRepository).markFailed(eq(QUEUE_ID), anyString());

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));

        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 verdict 应用失败")
                .hasCauseInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThat(ex.getCause())
                .hasMessageContaining("冲突裁决 REPLACE target 不存在或非 ACTIVE");
        org.assertj.core.api.Assertions.assertThat(ex.getSuppressed())
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(ex.getSuppressed()[0])
                .hasMessageContaining("队列失败标记写入失败");
        verify(queueRepository, never()).markResolved(anyString(), any());
        verify(semanticMemory, never()).updateLifecycleState(
                anyString(), any(LifecycleState.class), anyString(), any(ChangeSource.class));
    }

    // ------------------------------------------------------------------
    // 场景 7：target 不存在应 markFailed 并抛出
    // ------------------------------------------------------------------

    @Test
    @DisplayName("target不存在应markFailed并抛出")
    void target不存在应markFailed并抛出() {
        // given — TIMELINE verdict 但 target 已被删除
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        模拟LLM返回("""
                {"verdict":"TIMELINE","target_id":"old-1","rationale":"状态演化"}
                """);
        when(semanticMemory.findById(TARGET_ID)).thenReturn(Optional.empty());

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));
        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 verdict 应用失败");

        verify(semanticMemory, never()).updateSucceededBy(anyString(), anyString());
        verify(semanticMemory, never()).updateLifecycleState(
                anyString(), any(LifecycleState.class), anyString(), any(ChangeSource.class));
        verify(queueRepository).markFailed(eq(QUEUE_ID), anyString());
        verify(queueRepository, never()).markResolved(anyString(), any());
    }

    // ------------------------------------------------------------------
    // 场景 8：verdict JSON 解析失败应 markFailed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("verdict非法值应markFailed")
    void verdict非法值应markFailed() {
        // given
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        模拟LLM返回("""
                {"verdict":"UNKNOWN_KIND","target_id":"","rationale":"x"}
                """);

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));
        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 LLM 调用失败");

        verify(queueRepository).markFailed(eq(QUEUE_ID), anyString());
        verify(queueRepository, never()).markResolved(anyString(), any());
    }

    @Test
    @DisplayName("REPLACE缺targetId应markFailed")
    void replace缺targetId应markFailed() {
        // given
        var newEnt = newEntity();
        var oldEnt = buildEntity(TARGET_ID, LifecycleState.ACTIVE);
        模拟高相似度(TARGET_ID, 0.9f);
        when(queueRepository.enqueue(eq(NEW_ID), any())).thenReturn(QUEUE_ID);
        模拟LLM返回("""
                {"verdict":"REPLACE","rationale":"覆盖旧偏好"}
                """);

        var ex = assertThrows(IllegalStateException.class,
                () -> service.resolveSync(newEnt, List.of(oldEnt)));
        org.assertj.core.api.Assertions.assertThat(ex)
                .hasMessageContaining("冲突裁决 LLM 调用失败");

        verify(queueRepository).markFailed(eq(QUEUE_ID), anyString());
        verify(queueRepository, never()).markResolved(anyString(), any());
        verify(semanticMemory, never()).updateLifecycleState(
                anyString(), any(LifecycleState.class), anyString(), any(ChangeSource.class));
    }
}
