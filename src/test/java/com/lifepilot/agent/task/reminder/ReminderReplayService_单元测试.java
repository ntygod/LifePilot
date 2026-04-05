package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ReminderReplayService 单元测试。
 *
 * <p>验证离线回放会按历史序列积累样本，并产出动作漂移报告。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class ReminderReplayService_单元测试 {

    @Test
    void replay_历史轻提醒收益更高_会回放出动作降级() {
        ReminderExecutionRepository repository = mock(ReminderExecutionRepository.class);
        AgentConfigProperties config = new AgentConfigProperties();
        ReminderReplayService service = new ReminderReplayService(
                repository,
                new ReminderOpportunityPolicySelector(
                        new ReminderActionContextualBandit(0.15f, 3, 1, 1.0d),
                        0.72f,
                        0.34f,
                        0.08f
                ),
                new ReminderActionPolicySelector(
                        new ReminderActionContextualBandit(0.15f, 3, 1, 1.0d)
                ),
                new ReminderActionContextualBandit(0.15f, 3, 1, 1.0d),
                config
        );
        Instant since = Instant.parse("2026-03-01T00:00:00Z");
        when(repository.findReplaySamplesByUserIdSince(eq("default"), eq(since), anyInt()))
                .thenReturn(List.of(
                        sample("d1", Instant.parse("2026-03-20T12:00:00Z"),
                                ReminderAction.SOFT_PUSH, ReminderAction.SOFT_PUSH, true, false),
                        sample("d2", Instant.parse("2026-03-21T12:00:00Z"),
                                ReminderAction.SOFT_PUSH, ReminderAction.SOFT_PUSH, true, false),
                        sample("d3", Instant.parse("2026-03-22T12:00:00Z"),
                                ReminderAction.NORMAL_PUSH, ReminderAction.NORMAL_PUSH, false, true),
                        sample("d4", Instant.parse("2026-03-23T12:00:00Z"),
                                ReminderAction.NORMAL_PUSH, ReminderAction.NORMAL_PUSH, false, false)
                ));

        ReminderReplayReport report = service.replay("default", since, 20);

        assertThat(report.sampleCount()).isEqualTo(4);
        assertThat(report.actionShiftCount()).isGreaterThanOrEqualTo(1);
        assertThat(report.actionShiftMatrix()).containsKey("NORMAL_PUSH->SOFT_PUSH");
        assertThat(report.evaluations()).hasSize(4);
        assertThat(report.evaluations().getLast().replayedAction()).isEqualTo(ReminderAction.SOFT_PUSH);
        assertThat(report.replayedPushCount()).isEqualTo(4);
    }

    @Test
    void replay_无样本_返回空报告() {
        ReminderExecutionRepository repository = mock(ReminderExecutionRepository.class);
        Instant since = Instant.parse("2026-03-01T00:00:00Z");
        when(repository.findReplaySamplesByUserIdSince(eq("default"), eq(since), anyInt()))
                .thenReturn(List.of());

        ReminderReplayService service = new ReminderReplayService(repository, new AgentConfigProperties());
        ReminderReplayReport report = service.replay("default", since, 20);

        assertThat(report.sampleCount()).isZero();
        assertThat(report.evaluations()).isEmpty();
        assertThat(report.actionShiftMatrix()).isEqualTo(Map.of());
    }

    private ReminderReplaySample sample(String decisionId,
                                        Instant decidedAt,
                                        ReminderAction historicalAction,
                                        ReminderAction baseAction,
                                        boolean acted,
                                        boolean dismissed) {
        return new ReminderReplaySample(
                decisionId,
                decidedAt,
                "topic-" + decisionId,
                "周计划整理",
                "sig-" + decisionId,
                ReminderCandidateType.HABIT_WINDOW,
                historicalAction,
                baseAction,
                null,
                "命中高优先级提醒条件",
                0.78f,
                0.79f,
                0.81f,
                0.46f,
                0.74f,
                0.86f,
                0.02f,
                0.05f,
                0,
                2,
                1,
                0,
                0,
                0,
                acted,
                acted ? 1.0f : 0.0f,
                false,
                dismissed,
                false,
                !acted && !dismissed
        );
    }
}
