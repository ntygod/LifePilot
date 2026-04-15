package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.task.proactive.ContextPacket;
import com.lifepilot.agent.task.proactive.DeliveryLevel;
import com.lifepilot.agent.task.proactive.ProactiveCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReminderBehavior 单元测试。
 *
 * @author zsg
 * @since 2026-04-14
 */
class ReminderBehavior_单元测试 {

    ReminderSignalCollector signalCollector;
    ReminderCandidateDetector candidateDetector;
    ReminderMessageGenerator messageGenerator;
    ReminderBehavior behavior;

    @BeforeEach
    void setUp() {
        signalCollector = mock(ReminderSignalCollector.class);
        candidateDetector = mock(ReminderCandidateDetector.class);
        messageGenerator = mock(ReminderMessageGenerator.class);
        behavior = new ReminderBehavior(signalCollector, candidateDetector, messageGenerator);
    }

    @Test
    void 插件名称为reminder() {
        assertThat(behavior.name()).isEqualTo("reminder");
    }

    @Test
    void detect返回信号采集和候选检测的结果() {
        var signal = new ReminderSignal("s1", ReminderSignalKind.DEADLINE,
                0.8f, 0.7f, 2, Instant.now(), Instant.now().plusSeconds(3600),
                null, null, null, 0f, true, false, "deadline");
        var snapshot = new ReminderTopicSnapshot("topic-1", "任务截止",
                List.of(signal), ReminderTopicState.empty());
        when(signalCollector.collect(eq("u1"), any())).thenReturn(List.of(snapshot));

        var candidate = new ReminderCandidate("topic-1", "任务截止",
                ReminderCandidateType.DUE_SOON, "s1",
                0.7f, 0.8f, 0.9f, 0.5f, 1.0f, 0f, 0f, 0.75f,
                null, "截止临近");
        when(candidateDetector.detect(eq(snapshot), any(), any())).thenReturn(List.of(candidate));

        var ctx = testCtx();
        var result = behavior.detect(ctx);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().behaviorName()).isEqualTo("reminder");
        assertThat(result.getFirst().score()).isEqualTo(0.75f);
        assertThat(result.getFirst().topicKey()).isEqualTo("topic-1");
        assertThat(result.getFirst().detail()).isInstanceOf(ReminderCandidateDetail.class);
    }

    @Test
    void detect过滤已静音主题() {
        var state = new ReminderTopicState(null, 0, 0, 0, 0, 0, 0, true);
        var snapshot = new ReminderTopicSnapshot("muted-topic", "已静音", List.of(), state);
        when(signalCollector.collect(eq("u1"), any())).thenReturn(List.of(snapshot));

        var result = behavior.detect(testCtx());

        assertThat(result).isEmpty();
        verifyNoInteractions(candidateDetector);
    }

    @Test
    void detect无候选时返回空列表() {
        var snapshot = new ReminderTopicSnapshot("topic-1", "普通话题",
                List.of(), ReminderTopicState.empty());
        when(signalCollector.collect(eq("u1"), any())).thenReturn(List.of(snapshot));
        when(candidateDetector.detect(eq(snapshot), any(), any())).thenReturn(List.of());

        var result = behavior.detect(testCtx());

        assertThat(result).isEmpty();
    }

    @Test
    void reason生成消息并返回ProactiveAction() {
        var signal = new ReminderSignal("s1", ReminderSignalKind.DEADLINE,
                0.8f, 0.7f, 2, Instant.now(), Instant.now().plusSeconds(3600),
                null, null, null, 0f, true, false, "deadline");
        var snapshot = new ReminderTopicSnapshot("topic-1", "任务截止",
                List.of(signal), ReminderTopicState.empty());
        var reminderCandidate = new ReminderCandidate("topic-1", "任务截止",
                ReminderCandidateType.DUE_SOON, "s1",
                0.7f, 0.8f, 0.9f, 0.5f, 1.0f, 0f, 0f, 0.75f,
                null, "截止临近");
        var detail = new ReminderCandidateDetail(snapshot, reminderCandidate, new ReminderPolicyConfig());
        var proactiveCandidate = new ProactiveCandidate(
                "c1", "reminder", "topic-1", "任务截止", 0.75f, "截止临近", detail);

        when(messageGenerator.generate(eq("u1"), any(), eq(snapshot), any()))
                .thenReturn(new ReminderMessage("关于「任务截止」，现在处理会更从容。", "fallback", null, null));

        var ctx = testCtx();
        var actions = behavior.reason(List.of(proactiveCandidate), ctx);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("任务截止");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.INTERRUPT);
    }

    @Test
    void reason跳过空消息() {
        var snapshot = new ReminderTopicSnapshot("topic-1", "标题",
                List.of(), ReminderTopicState.empty());
        var reminderCandidate = new ReminderCandidate("topic-1", "标题",
                ReminderCandidateType.DUE_SOON, "s1",
                0.7f, 0.8f, 0.9f, 0.5f, 1.0f, 0f, 0f, 0.6f,
                null, "理由");
        var detail = new ReminderCandidateDetail(snapshot, reminderCandidate, new ReminderPolicyConfig());
        var proactiveCandidate = new ProactiveCandidate(
                "c1", "reminder", "topic-1", "标题", 0.6f, "理由", detail);

        when(messageGenerator.generate(any(), any(), any(), any()))
                .thenReturn(new ReminderMessage("", "fallback", null, null));

        var actions = behavior.reason(List.of(proactiveCandidate), testCtx());

        assertThat(actions).isEmpty();
    }

    @Test
    void reason低分候选建议QUEUE级别() {
        var snapshot = new ReminderTopicSnapshot("topic-1", "标题",
                List.of(), ReminderTopicState.empty());
        var reminderCandidate = new ReminderCandidate("topic-1", "标题",
                ReminderCandidateType.COMMITMENT_GAP, "s1",
                0.5f, 0.4f, 0.3f, 0.5f, 0.8f, 0f, 0f, 0.42f,
                null, "理由");
        var detail = new ReminderCandidateDetail(snapshot, reminderCandidate, new ReminderPolicyConfig());
        var proactiveCandidate = new ProactiveCandidate(
                "c1", "reminder", "topic-1", "标题", 0.42f, "理由", detail);

        when(messageGenerator.generate(any(), any(), any(), any()))
                .thenReturn(new ReminderMessage("提醒内容", "fallback", null, null));

        var actions = behavior.reason(List.of(proactiveCandidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.QUEUE);
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null);
    }
}
