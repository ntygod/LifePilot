package com.lifepilot.memory.lifecycle;

import org.junit.jupiter.api.Test;
import static com.lifepilot.memory.lifecycle.LifecycleState.*;
import static org.assertj.core.api.Assertions.*;

class 状态机转换_单元测试 {
    @Test
    void ACTIVE可以转到所有非ACTIVE状态() {
        for (LifecycleState s : LifecycleState.values()) {
            if (s == ACTIVE) continue;
            assertThat(ACTIVE.canTransitionTo(s)).as("ACTIVE → %s", s).isTrue();
        }
    }

    @Test
    void ARCHIVED是终态不能再转() {
        for (LifecycleState s : LifecycleState.values()) {
            assertThat(ARCHIVED.canTransitionTo(s)).as("ARCHIVED → %s", s).isFalse();
        }
    }

    @Test
    void COMPLETED只能到ARCHIVED() {
        assertThat(COMPLETED.canTransitionTo(ARCHIVED)).isTrue();
        assertThat(COMPLETED.canTransitionTo(CANCELLED)).isFalse();
        assertThat(COMPLETED.canTransitionTo(ACTIVE)).isFalse();
    }

    @Test
    void REGENERATION_NEEDED只能到SUPERSEDED或ARCHIVED() {
        assertThat(REGENERATION_NEEDED.canTransitionTo(SUPERSEDED)).isTrue();
        assertThat(REGENERATION_NEEDED.canTransitionTo(ARCHIVED)).isTrue();
        assertThat(REGENERATION_NEEDED.canTransitionTo(ACTIVE)).isFalse();
    }

    @Test
    void 可召回集是ACTIVE_COMPLETED_REGENERATION_NEEDED() {
        assertThat(ACTIVE.isRetrievable()).isTrue();
        assertThat(COMPLETED.isRetrievable()).isTrue();
        assertThat(REGENERATION_NEEDED.isRetrievable()).isTrue();
        assertThat(CANCELLED.isRetrievable()).isFalse();
        assertThat(EXPIRED.isRetrievable()).isFalse();
        assertThat(SUPERSEDED.isRetrievable()).isFalse();
        assertThat(ARCHIVED.isRetrievable()).isFalse();
    }
}
