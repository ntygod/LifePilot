package com.lifepilot.agent.task.reminder.timing;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderAction;
import com.lifepilot.agent.task.reminder.ReminderCandidate;
import com.lifepilot.agent.task.reminder.ReminderCandidateType;
import com.lifepilot.agent.task.reminder.ReminderExecutionRepository;
import com.lifepilot.agent.task.reminder.ReminderReplaySample;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GoldilocksWindowCalculator 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class GoldilocksWindowCalculator_单元测试 {

    @Test
    void 开关关闭时isWindowClosed始终返回false() {
        var repo = mock(ReminderExecutionRepository.class);
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveTimingWindowEnabled(false);
        var calc = new GoldilocksWindowCalculator(repo, cfg);

        var candidate = candidate(ReminderCandidateType.DUE_SOON, Instant.now().minusSeconds(3600));
        assertThat(calc.isWindowClosed("u1", candidate, Instant.now())).isFalse();
    }

    @Test
    void suggestedAt为null时返回false() {
        var repo = mock(ReminderExecutionRepository.class);
        var cfg = new AgentConfigProperties().getTask();
        var calc = new GoldilocksWindowCalculator(repo, cfg);

        var candidate = candidate(ReminderCandidateType.DUE_SOON, null);
        assertThat(calc.isWindowClosed("u1", candidate, Instant.now())).isFalse();
    }

    @Test
    void 非支持类型返回false() {
        var repo = mock(ReminderExecutionRepository.class);
        var cfg = new AgentConfigProperties().getTask();
        var calc = new GoldilocksWindowCalculator(repo, cfg);

        // HABIT_WINDOW 不在支持列表
        var candidate = candidate(ReminderCandidateType.HABIT_WINDOW, Instant.now().minusSeconds(3600));
        assertThat(calc.isWindowClosed("u1", candidate, Instant.now())).isFalse();
    }

    @Test
    void now在suggestedAt之前_窗口未关闭() {
        var repo = mock(ReminderExecutionRepository.class);
        when(repo.findReplaySamplesByUserIdSince(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of());
        var cfg = new AgentConfigProperties().getTask();
        var calc = new GoldilocksWindowCalculator(repo, cfg);

        Instant suggestedAt = Instant.now().plusSeconds(3600);  // 1h 后
        var candidate = candidate(ReminderCandidateType.DUE_SOON, suggestedAt);

        assertThat(calc.isWindowClosed("u1", candidate, Instant.now())).isFalse();
    }

    @Test
    void now远超suggestedAt_窗口已关闭() {
        var repo = mock(ReminderExecutionRepository.class);
        when(repo.findReplaySamplesByUserIdSince(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of());
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveTimingDefaultResponseLatencyMinutes(30);
        var calc = new GoldilocksWindowCalculator(repo, cfg);

        // suggestedAt 2 小时前，默认 grace 约 30min × 0.8 = 24min，远远超过
        Instant suggestedAt = Instant.now().minus(Duration.ofHours(2));
        var candidate = candidate(ReminderCandidateType.DUE_SOON, suggestedAt);

        assertThat(calc.isWindowClosed("u1", candidate, Instant.now())).isTrue();
    }

    @Test
    void 样本不足时使用默认延迟() {
        var repo = mock(ReminderExecutionRepository.class);
        // 只返回 2 条 acted 样本，低于默认 min=5
        when(repo.findReplaySamplesByUserIdSince(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of());
        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveTimingDefaultResponseLatencyMinutes(45);
        var calc = new GoldilocksWindowCalculator(repo, cfg);

        var latency = calc.estimateResponseLatency("u1");
        assertThat(latency.toMinutes()).isEqualTo(45);
    }

    @Test
    void 样本充足时估算p80延迟() {
        var repo = mock(ReminderExecutionRepository.class);
        // 构造一批 acted 样本，时间差依次 10, 20, 30, 40, 50, 60 分钟
        Instant base = Instant.parse("2026-05-01T08:00:00Z");
        var samples = new ArrayList<ReminderReplaySample>();
        int[] gaps = {10, 20, 30, 40, 50, 60, 10};
        Instant prev = base;
        for (int i = 0; i < gaps.length; i++) {
            Instant at = prev.plus(Duration.ofMinutes(gaps[i]));
            samples.add(sampleActed(at));
            prev = at;
        }
        when(repo.findReplaySamplesByUserIdSince(anyString(), any(Instant.class), anyInt()))
                .thenReturn(samples);

        var cfg = new AgentConfigProperties().getTask();
        cfg.setProactiveTimingResponseLatencyMinSamples(3);
        cfg.setProactiveTimingDefaultResponseLatencyMinutes(30);
        var calc = new GoldilocksWindowCalculator(repo, cfg);

        var latency = calc.estimateResponseLatency("u1");
        // p80 of {10,20,30,40,50,60,10} 排序后 {10,10,20,30,40,50,60}，p80 index = ceil(7*0.8)-1 = 5 → 50
        assertThat(latency.toMinutes()).isBetween(40L, 60L);
    }

    @Test
    void 空用户返回默认延迟() {
        var repo = mock(ReminderExecutionRepository.class);
        var cfg = new AgentConfigProperties().getTask();
        var calc = new GoldilocksWindowCalculator(repo, cfg);

        var latency = calc.estimateResponseLatency(null);
        assertThat(latency.toMinutes()).isEqualTo(30);
    }

    private ReminderCandidate candidate(ReminderCandidateType type, Instant suggestedAt) {
        return new ReminderCandidate(
                "topic-1", "测试", type, "signal-1",
                0.7f, 0.8f, 0.6f, 0.5f, 0.9f,
                0.0f, 0.0f, 0.7f, suggestedAt, "测试理由"
        );
    }

    private ReminderReplaySample sampleActed(Instant decidedAt) {
        return new ReminderReplaySample(
                "id-" + decidedAt.toEpochMilli(),
                decidedAt,
                "topic",
                "title",
                "signal",
                ReminderCandidateType.DUE_SOON,
                ReminderAction.NORMAL_PUSH,
                ReminderAction.NORMAL_PUSH,
                null,
                "test",
                0.7f,
                0.7f, 0.7f, 0.7f, 0.7f, 0.7f,
                0.0f, 0.0f,
                0, 0, 0, 0, 0, 0,
                true, 1.0f, false, false, false, false
        );
    }
}
