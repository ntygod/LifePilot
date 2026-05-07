package com.lifepilot.memory.lifecycle;

import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.lifecycle.listeners.VectorListener;
import com.lifepilot.memory.projection.MemoryProjectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link VectorListener} 单元测试 —— 验证非活状态登记向量删除投影任务，COMPLETED/ACTIVE 保留向量。
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
class VectorListener_单元测试 {

    @Mock
    MemoryProjectionService projectionService;

    @InjectMocks
    VectorListener listener;

    @Test
    void 转EXPIRED应删除向量() {
        var event = new EntityLifecycleChanged(
                "e-1", "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.EXPIRED,
                "ttl", ChangeSource.CRON_EXPIRE);

        listener.onLifecycleChanged(event);

        verify(projectionService).enqueueVectorDeleteAfterCommit("e-1");
    }

    @Test
    void 转CANCELLED应删除向量() {
        var event = new EntityLifecycleChanged(
                "e-2", "GOAL",
                LifecycleState.ACTIVE, LifecycleState.CANCELLED,
                "user-cancel", ChangeSource.TOOL_EXPLICIT);

        listener.onLifecycleChanged(event);

        verify(projectionService).enqueueVectorDeleteAfterCommit("e-2");
    }

    @Test
    void 转SUPERSEDED应删除向量() {
        var event = new EntityLifecycleChanged(
                "e-3", "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.SUPERSEDED,
                "replaced", ChangeSource.CONFLICT_RESOLVE);

        listener.onLifecycleChanged(event);

        verify(projectionService).enqueueVectorDeleteAfterCommit("e-3");
    }

    @Test
    void 转ARCHIVED应删除向量() {
        var event = new EntityLifecycleChanged(
                "e-4", "GOAL",
                LifecycleState.COMPLETED, LifecycleState.ARCHIVED,
                "archived", ChangeSource.UI_EDIT);

        listener.onLifecycleChanged(event);

        verify(projectionService).enqueueVectorDeleteAfterCommit("e-4");
    }

    @Test
    void 转REGENERATION_NEEDED应删除向量() {
        var event = new EntityLifecycleChanged(
                "e-5", "EXPERIENCE",
                LifecycleState.ACTIVE, LifecycleState.REGENERATION_NEEDED,
                "source-stale", ChangeSource.DERIVATION_TRIGGER);

        listener.onLifecycleChanged(event);

        verify(projectionService).enqueueVectorDeleteAfterCommit("e-5");
    }

    @Test
    void 转COMPLETED保留向量() {
        var event = new EntityLifecycleChanged(
                "e-6", "GOAL",
                LifecycleState.ACTIVE, LifecycleState.COMPLETED,
                "done", ChangeSource.TOOL_EXPLICIT);

        listener.onLifecycleChanged(event);

        verifyNoInteractions(projectionService);
    }

    @Test
    void 保持ACTIVE不触发向量操作() {
        var event = new EntityLifecycleChanged(
                "e-7", "PREFERENCE",
                null, LifecycleState.ACTIVE,
                "created", ChangeSource.LLM_SEMANTIC);

        listener.onLifecycleChanged(event);

        verifyNoInteractions(projectionService);
    }

    @Test
    void 投影登记失败应向上抛出() {
        doThrow(new RuntimeException("sqlite-vec 未加载"))
                .when(projectionService).enqueueVectorDeleteAfterCommit("e-8");
        var event = new EntityLifecycleChanged(
                "e-8", "EXPERIENCE",
                LifecycleState.ACTIVE, LifecycleState.EXPIRED,
                "ttl", ChangeSource.CRON_EXPIRE);

        assertThatThrownBy(() -> listener.onLifecycleChanged(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sqlite-vec 未加载");
        verify(projectionService).enqueueVectorDeleteAfterCommit("e-8");
    }
}
