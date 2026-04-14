package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardBehavior_单元测试 {

    ClipboardIntentBuffer buffer;
    ClipboardBehavior behavior;

    @BeforeEach
    void setUp() {
        buffer = new ClipboardIntentBuffer();
        behavior = new ClipboardBehavior(buffer);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("clipboard");
    }

    @Test
    void detect_缓冲区为空时返回空() {
        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void detect_有快递单号意图时返回候选() {
        buffer.offer(new ReminderClipboardIntent(
                ReminderClipboardIntentType.TRACKING_NUMBER, "SF1234567890123", Instant.now()));

        var candidates = behavior.detect(testCtx());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().title()).contains("快递");
    }

    @Test
    void detect_消费后缓冲区清空() {
        buffer.offer(new ReminderClipboardIntent(
                ReminderClipboardIntentType.FLIGHT_NUMBER, "CA1234", Instant.now()));

        behavior.detect(testCtx());
        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void reason_生成快递单号建议() {
        var candidate = new ProactiveCandidate("c1", "clipboard", "tracking-SF123",
                "快递单号: SF1234567890123", 0.6f, "TRACKING_NUMBER",
                new ReminderClipboardIntent(ReminderClipboardIntentType.TRACKING_NUMBER,
                        "SF1234567890123", Instant.now()));

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("SF1234567890123");
        assertThat(actions.getFirst().content()).contains("物流");
    }

    @Test
    void reason_生成航班号建议() {
        var candidate = new ProactiveCandidate("c1", "clipboard", "flight-CA1234",
                "航班号: CA1234", 0.6f, "FLIGHT_NUMBER",
                new ReminderClipboardIntent(ReminderClipboardIntentType.FLIGHT_NUMBER,
                        "CA1234", Instant.now()));

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("航班");
    }

    @Test
    void 缓冲区容量限制() {
        for (int i = 0; i < 25; i++) {
            buffer.offer(new ReminderClipboardIntent(
                    ReminderClipboardIntentType.PHONE, "1380000" + i, Instant.now()));
        }
        // 最多保留 20 条
        var drained = buffer.drainAll();
        assertThat(drained.size()).isLessThanOrEqualTo(20);
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30);
    }
}
