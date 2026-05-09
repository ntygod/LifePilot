package com.lifepilot.memory.eval.runner;

import com.lifepilot.memory.eval.loader.BenchmarkCase;
import com.lifepilot.memory.eval.loader.BenchmarkMessage;
import com.lifepilot.memory.eval.loader.BenchmarkSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConversationReplayer} 单元测试（不依赖 Spring Boot 启动）。
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("ConversationReplayer 单元测试")
class ConversationReplayerTests {

    @Test
    @DisplayName("pairMessages: 奇数消息 → 最后一条独立 + 空 assistant")
    void pairMessages_奇数配对() {
        var m1 = new BenchmarkMessage("user", "hello", null);
        var m2 = new BenchmarkMessage("user", "hi", null);
        var m3 = new BenchmarkMessage("user", "last", null);
        List<ConversationReplayer.Pair> pairs =
                ConversationReplayer.pairMessages(List.of(m1, m2, m3));
        assertThat(pairs).hasSize(2);
        assertThat(pairs.get(0).userMessage()).isEqualTo("hello");
        assertThat(pairs.get(0).assistantMessage()).isEqualTo("hi");
        assertThat(pairs.get(1).userMessage()).isEqualTo("last");
        assertThat(pairs.get(1).assistantMessage()).isEmpty();
    }

    @Test
    @DisplayName("pairMessages: 偶数消息 → round-robin 配对")
    void pairMessages_偶数配对() {
        var m1 = new BenchmarkMessage("user", "q1", null);
        var m2 = new BenchmarkMessage("assistant", "a1", null);
        var m3 = new BenchmarkMessage("user", "q2", null);
        var m4 = new BenchmarkMessage("assistant", "a2", null);
        List<ConversationReplayer.Pair> pairs =
                ConversationReplayer.pairMessages(List.of(m1, m2, m3, m4));
        assertThat(pairs).hasSize(2);
        assertThat(pairs.get(0).userMessage()).isEqualTo("q1");
        assertThat(pairs.get(0).assistantMessage()).isEqualTo("a1");
        assertThat(pairs.get(1).userMessage()).isEqualTo("q2");
        assertThat(pairs.get(1).assistantMessage()).isEqualTo("a2");
    }

    @Test
    @DisplayName("noop: 所有依赖为 null 时，replay 不抛异常")
    void noop_安全执行() {
        ConversationReplayer noop = ConversationReplayer.noop();
        noop.replay(new BenchmarkCase("case", "test",
                List.of(new BenchmarkSession("s", null,
                        List.of(new BenchmarkMessage("user", "hi", null)))),
                List.of(), null));
        // 无异常即通过
    }

    @Test
    @DisplayName("构造：null 依赖组合可用，且可正常触发 pairMessages 逻辑")
    void null_依赖_可用() {
        ConversationReplayer replayer = new ConversationReplayer(
                null, null, null, Clock.systemUTC(), 1000L);
        BenchmarkCase bc = new BenchmarkCase(
                "case", "test",
                List.of(new BenchmarkSession("s0", null, List.of(
                        new BenchmarkMessage("user", "q", null),
                        new BenchmarkMessage("assistant", "a", null)))),
                List.of(), null);
        replayer.replay(bc);
        replayer.awaitPendingExtractions();
    }

    @Test
    @DisplayName("pairMessages: 空列表返回空")
    void pairMessages_空列表() {
        assertThat(ConversationReplayer.pairMessages(List.of())).isEmpty();
    }
}
