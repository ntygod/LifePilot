package com.lifepilot.memory.lifecycle;

import com.lifepilot.memory.lifecycle.events.ProactiveTaskCancelled;
import com.lifepilot.memory.lifecycle.listeners.ProactiveTaskCancelListener;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProactiveTaskCancelListener} 单元测试 —— 验证 ACTIVE insight 级联 CANCELLED、
 * 非 ACTIVE 幂等跳过、实体缺失不抛异常、空列表短路。
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProactiveTaskCancelListener 单元测试")
class ProactiveTaskCancelListener_单元测试 {

    @Mock
    SemanticMemory semanticMemory;

    @InjectMocks
    ProactiveTaskCancelListener listener;

    @Test
    void 主动任务取消应对每个ACTIVE_insight转CANCELLED() {
        when(semanticMemory.findById("ins-1")).thenReturn(Optional.of(构造实体("ins-1", LifecycleState.ACTIVE)));
        when(semanticMemory.findById("ins-2")).thenReturn(Optional.of(构造实体("ins-2", LifecycleState.ACTIVE)));

        listener.onCancelled(new ProactiveTaskCancelled("t-1", List.of("ins-1", "ins-2")));

        verify(semanticMemory).updateLifecycleState("ins-1",
                LifecycleState.CANCELLED, "proactive-task-cancelled:t-1", ChangeSource.PROACTIVE_CANCEL);
        verify(semanticMemory).updateLifecycleState("ins-2",
                LifecycleState.CANCELLED, "proactive-task-cancelled:t-1", ChangeSource.PROACTIVE_CANCEL);
    }

    @Test
    void 非ACTIVE_insight跳过() {
        when(semanticMemory.findById("ins-3"))
                .thenReturn(Optional.of(构造实体("ins-3", LifecycleState.CANCELLED)));

        listener.onCancelled(new ProactiveTaskCancelled("t-2", List.of("ins-3")));

        verify(semanticMemory, never())
                .updateLifecycleState(anyString(), any(), any(), any());
    }

    @Test
    void insight不存在不抛异常() {
        when(semanticMemory.findById("ghost")).thenReturn(Optional.empty());

        listener.onCancelled(new ProactiveTaskCancelled("t-3", List.of("ghost")));

        verify(semanticMemory, never())
                .updateLifecycleState(anyString(), any(), any(), any());
    }

    @Test
    void 空insight列表不调任何方法() {
        listener.onCancelled(new ProactiveTaskCancelled("t-4", List.of()));

        verify(semanticMemory, never()).findById(anyString());
        verify(semanticMemory, never())
                .updateLifecycleState(anyString(), any(), any(), any());
    }

    // ---------- 测试夹具 ----------

    private TemporalEntity 构造实体(String id, LifecycleState state) {
        Instant now = Instant.parse("2026-04-23T10:00:00Z");
        return new TemporalEntity(
                id, EntityType.GOAL, "name-" + id, "desc-" + id,
                Map.of(), 1, true, now, null, null,
                0.9f, 0.5f, 0, null, now, now,
                state, null, null, Temporality.PERSISTENT,
                null, false, List.of());
    }
}
