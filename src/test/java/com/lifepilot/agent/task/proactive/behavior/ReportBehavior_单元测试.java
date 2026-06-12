package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReportBehavior_单元测试 {

    EpisodicMemory episodicMemory;
    ReportBehavior behavior;

    @BeforeEach
    void setUp() {
        episodicMemory = mock(EpisodicMemory.class);
        behavior = new ReportBehavior(episodicMemory, null, null, null);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("report");
    }

    @Test
    void detect_在日报时段返回候选() {
        // 20:30 CST = 12:30 UTC
        var ctx = new ContextPacket("u1", Instant.parse("2026-04-14T12:30:00Z"),
                ZoneId.of("Asia/Shanghai"), null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);

        var candidates = behavior.detect(ctx);

        assertThat(candidates).anyMatch(c -> c.title().equals("今日小结"));
    }

    @Test
    void detect_非日报时段返回空() {
        // 15:00 CST = 07:00 UTC
        var ctx = new ContextPacket("u1", Instant.parse("2026-04-14T07:00:00Z"),
                ZoneId.of("Asia/Shanghai"), null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);

        assertThat(behavior.detect(ctx)).isEmpty();
    }

    @Test
    void detect_周五日报时段同时返回周报() {
        // 2026-04-17 is Friday, 20:30 CST
        var friday = LocalDate.of(2026, 4, 17).atTime(20, 30)
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        var ctx = new ContextPacket("u1", friday, ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);

        var candidates = behavior.detect(ctx);

        assertThat(candidates).hasSize(2);
        assertThat(candidates).anyMatch(c -> c.title().equals("今日小结"));
        assertThat(candidates).anyMatch(c -> c.title().equals("本周总结"));
    }

    @Test
    void reason_无对话时返回空() {
        when(episodicMemory.getRecent(any(Duration.class))).thenReturn(List.of());
        var candidate = new ProactiveCandidate("c1", "report", "daily-report",
                "今日小结", 0.5f, "每日报告时段", "daily");

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).isEmpty();
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);
    }
}
