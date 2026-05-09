package com.lifepilot.agent.task.reminder.timing;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderAction;
import com.lifepilot.agent.task.reminder.ReminderCandidate;
import com.lifepilot.agent.task.reminder.ReminderCandidateDetector;
import com.lifepilot.agent.task.reminder.ReminderCandidateType;
import com.lifepilot.agent.task.reminder.ReminderDecision;
import com.lifepilot.agent.task.reminder.ReminderDecisionEngine;
import com.lifepilot.agent.task.reminder.ReminderExecutionRepository;
import com.lifepilot.agent.task.reminder.ReminderPolicyConfig;
import com.lifepilot.agent.task.reminder.ReminderRuntimeContext;
import com.lifepilot.agent.task.reminder.ReminderSignal;
import com.lifepilot.agent.task.reminder.ReminderSignalKind;
import com.lifepilot.agent.task.reminder.ReminderSkipReason;
import com.lifepilot.agent.task.reminder.ReminderTopicSnapshot;
import com.lifepilot.agent.task.reminder.ReminderTopicState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ReminderDecisionEngine × GoldilocksWindowCalculator 集成测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class ReminderDecisionEngine_Goldilocks_集成测试 {

    @Test
    void 候选窗口关闭时_决策引擎返回WINDOW_CLOSED跳过() {
        var repo = mock(ReminderExecutionRepository.class);
        when(repo.findReplaySamplesByUserIdSince(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of());

        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveTimingWindowEnabled(true);
        cfg.setProactiveTimingDefaultResponseLatencyMinutes(30);

        var calc = new GoldilocksWindowCalculator(repo, cfg);
        var detector = mock(ReminderCandidateDetector.class);

        // suggestedAt 已过 2 小时 → 肯定窗口关闭
        Instant now = Instant.parse("2026-05-09T10:00:00Z");
        Instant suggestedAt = now.minus(Duration.ofHours(2));

        var signal = new ReminderSignal(
                "sig-1", ReminderSignalKind.DEADLINE,
                0.8f, 0.8f, 3,
                now.minus(Duration.ofHours(3)),
                suggestedAt,
                Duration.ofMinutes(30),
                null, null,
                0.0f, true, false, "test signal"
        );
        var topicSnapshot = snapshot();
        var candidate = new ReminderCandidate(
                "topic-1", "测试", ReminderCandidateType.DUE_SOON, signal.signalId(),
                0.7f, 0.8f, 0.6f, 0.5f, 0.9f,
                0.0f, 0.0f, 0.7f, suggestedAt, "测试理由"
        );

        when(detector.detect(any(), any(), any()))
                .thenReturn(List.of(candidate));

        var engine = new ReminderDecisionEngine(detector, null, calc, () -> "u1");
        var context = new ReminderRuntimeContext(now, ZoneId.systemDefault(),
                null, null, 0, null);
        var config = policy();

        Optional<ReminderDecision> decision = engine.evaluateTopic(topicSnapshot, context, config);
        assertThat(decision).isPresent();
        assertThat(decision.get().action()).isEqualTo(ReminderAction.SKIP);
        assertThat(decision.get().skipReason()).isEqualTo(ReminderSkipReason.WINDOW_CLOSED);
    }

    @Test
    void calculator为null时_行为与前版本一致() {
        var detector = mock(ReminderCandidateDetector.class);
        when(detector.detect(any(), any(), any())).thenReturn(List.of());

        var engine = new ReminderDecisionEngine(detector, null, null, null);
        var topicSnapshot = snapshot();
        var config = policy();
        var context = new ReminderRuntimeContext(Instant.now(), ZoneId.systemDefault(),
                null, null, 0, null);

        Optional<ReminderDecision> decision = engine.evaluateTopic(topicSnapshot, context, config);
        // 无候选 → 无决策
        assertThat(decision).isEmpty();
    }

    private ReminderTopicSnapshot snapshot() {
        var state = new ReminderTopicState(
                null, 0, 0, 0, 0, 0, 0, false);
        return new ReminderTopicSnapshot(
                "topic-1", "测试主题", List.of(), state);
    }

    private ReminderPolicyConfig policy() {
        return new ReminderPolicyConfig(
                24, 18, 5, 12, 60,
                0.55f, 0.63f, 0.78f, 0.65f);
    }
}
