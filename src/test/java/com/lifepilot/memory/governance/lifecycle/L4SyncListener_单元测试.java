package com.lifepilot.memory.governance.lifecycle;

import com.lifepilot.memory.governance.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.governance.lifecycle.listeners.L4SyncListener;
import com.lifepilot.memory.store.procedural.PreferenceRuleRepository;
import com.lifepilot.memory.store.procedural.ProceduralMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link L4SyncListener} 单元测试 —— 验证 L3 生命周期转非活状态时对 L4 仓库的联动失活调用。
 *
 * <p>所有断言基于 Mockito verify；单元测试不涉及真实 JDBC 与事务。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
class L4SyncListener_单元测试 {

    @Mock
    PreferenceRuleRepository ruleRepo;

    @Mock
    ProceduralMemoryRepository procedureRepo;

    @InjectMocks
    L4SyncListener listener;

    @Test
    void 源实体转CANCELLED应使对应preference_rules失活() {
        var event = new EntityLifecycleChanged(
                "e-1", "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.CANCELLED,
                "user-cancel", ChangeSource.TOOL_EXPLICIT);

        listener.onLifecycleChanged(event);

        verify(ruleRepo).deactivateBySourceEntity("e-1", "user-cancel");
        verify(procedureRepo).deactivateBySourceEntity("e-1", "user-cancel");
    }

    @Test
    void 源实体转ACTIVE不触发失活() {
        var event = new EntityLifecycleChanged(
                "e-2", "GOAL",
                null, LifecycleState.ACTIVE,
                "created", ChangeSource.LLM_SEMANTIC);

        listener.onLifecycleChanged(event);

        verifyNoInteractions(ruleRepo, procedureRepo);
    }

    @Test
    void 源实体转COMPLETED不触发失活() {
        var event = new EntityLifecycleChanged(
                "e-3", "GOAL",
                LifecycleState.ACTIVE, LifecycleState.COMPLETED,
                "done", ChangeSource.TOOL_EXPLICIT);

        listener.onLifecycleChanged(event);

        verifyNoInteractions(ruleRepo, procedureRepo);
    }

    @Test
    void 新状态ARCHIVED也触发失活() {
        var event = new EntityLifecycleChanged(
                "e-4", "GOAL",
                LifecycleState.COMPLETED, LifecycleState.ARCHIVED,
                "goal-archived", ChangeSource.UI_EDIT);

        listener.onLifecycleChanged(event);

        verify(ruleRepo).deactivateBySourceEntity("e-4", "goal-archived");
        verify(procedureRepo).deactivateBySourceEntity("e-4", "goal-archived");
    }

    @Test
    void 新状态REGENERATION_NEEDED也触发失活() {
        var event = new EntityLifecycleChanged(
                "e-5", "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.REGENERATION_NEEDED,
                "derivation-source-stale", ChangeSource.DERIVATION_TRIGGER);

        listener.onLifecycleChanged(event);

        verify(ruleRepo).deactivateBySourceEntity("e-5", "derivation-source-stale");
        verify(procedureRepo).deactivateBySourceEntity("e-5", "derivation-source-stale");
    }

    @Test
    void reason为null时使用newState名称作为理由() {
        var event = new EntityLifecycleChanged(
                "e-6", "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.SUPERSEDED,
                null, ChangeSource.CONFLICT_RESOLVE);

        listener.onLifecycleChanged(event);

        verify(ruleRepo).deactivateBySourceEntity("e-6", "SUPERSEDED");
        verify(procedureRepo).deactivateBySourceEntity("e-6", "SUPERSEDED");
    }

    @Test
    void preference仓库失败应直接抛出且不继续procedure仓库调用() {
        doThrow(new RuntimeException("DB 忙"))
                .when(ruleRepo).deactivateBySourceEntity("e-7", "expired");
        var event = new EntityLifecycleChanged(
                "e-7", "GOAL",
                LifecycleState.ACTIVE, LifecycleState.EXPIRED,
                "expired", ChangeSource.CRON_EXPIRE);

        assertThatThrownBy(() -> listener.onLifecycleChanged(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB 忙");

        verify(procedureRepo, never()).deactivateBySourceEntity("e-7", "expired");
    }
}
