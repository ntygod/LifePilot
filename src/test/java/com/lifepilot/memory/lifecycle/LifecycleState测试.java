package com.lifepilot.memory.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LifecycleState} 转换矩阵测试，重点覆盖新增的 STALE_CANDIDATE。
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("LifecycleState 单元测试")
class LifecycleState测试 {

    @Test
    @DisplayName("STALE_CANDIDATE isRetrievable → true")
    void stale_candidate_可召回() {
        assertThat(LifecycleState.STALE_CANDIDATE.isRetrievable()).isTrue();
    }

    @Test
    @DisplayName("ACTIVE → STALE_CANDIDATE 允许")
    void active_可进入_stale_candidate() {
        assertThat(LifecycleState.ACTIVE.canTransitionTo(LifecycleState.STALE_CANDIDATE)).isTrue();
    }

    @Test
    @DisplayName("STALE_CANDIDATE → ACTIVE 允许（用户确认仍有效）")
    void stale_可回到_active() {
        assertThat(LifecycleState.STALE_CANDIDATE.canTransitionTo(LifecycleState.ACTIVE)).isTrue();
    }

    @Test
    @DisplayName("STALE_CANDIDATE → SUPERSEDED 允许")
    void stale_可转_superseded() {
        assertThat(LifecycleState.STALE_CANDIDATE.canTransitionTo(LifecycleState.SUPERSEDED)).isTrue();
    }

    @Test
    @DisplayName("STALE_CANDIDATE → ARCHIVED 允许")
    void stale_可归档() {
        assertThat(LifecycleState.STALE_CANDIDATE.canTransitionTo(LifecycleState.ARCHIVED)).isTrue();
    }

    @Test
    @DisplayName("STALE_CANDIDATE → COMPLETED / CANCELLED / EXPIRED / REGENERATION_NEEDED 不允许")
    void stale_不可转_其他态() {
        assertThat(LifecycleState.STALE_CANDIDATE.canTransitionTo(LifecycleState.COMPLETED)).isFalse();
        assertThat(LifecycleState.STALE_CANDIDATE.canTransitionTo(LifecycleState.CANCELLED)).isFalse();
        assertThat(LifecycleState.STALE_CANDIDATE.canTransitionTo(LifecycleState.EXPIRED)).isFalse();
        assertThat(LifecycleState.STALE_CANDIDATE.canTransitionTo(LifecycleState.REGENERATION_NEEDED)).isFalse();
    }

    @Test
    @DisplayName("COMPLETED / CANCELLED / EXPIRED / REGENERATION_NEEDED → STALE_CANDIDATE 不允许")
    void 其他态_不可进入_stale() {
        assertThat(LifecycleState.COMPLETED.canTransitionTo(LifecycleState.STALE_CANDIDATE)).isFalse();
        assertThat(LifecycleState.CANCELLED.canTransitionTo(LifecycleState.STALE_CANDIDATE)).isFalse();
        assertThat(LifecycleState.EXPIRED.canTransitionTo(LifecycleState.STALE_CANDIDATE)).isFalse();
        assertThat(LifecycleState.REGENERATION_NEEDED.canTransitionTo(LifecycleState.STALE_CANDIDATE)).isFalse();
    }

    @Test
    @DisplayName("ARCHIVED 是终态，不可转任何态")
    void archived_终态不变() {
        for (LifecycleState next : LifecycleState.values()) {
            assertThat(LifecycleState.ARCHIVED.canTransitionTo(next)).isFalse();
        }
    }
}
