package com.lifepilot.agent.initiative.gate;

import com.lifepilot.agent.initiative.model.Evidence;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtKind;
import com.lifepilot.agent.initiative.model.ThoughtState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gatekeeper 单元测试。
 *
 * @author zsg
 * @since 2026-06-20
 */
class Gatekeeper_单元测试 {

    private static final Instant NOW = Instant.parse("2026-06-20T23:30:00Z");

    @Test
    void 普通想法在静默时段等待() {
        var gatekeeper = new Gatekeeper(3, Duration.ZERO, LocalTime.of(23, 0), LocalTime.of(8, 0),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var thought = thought(0.8f, NOW.plus(Duration.ofHours(6)));

        var decision = gatekeeper.evaluate(thought, new Gatekeeper.GatekeeperContext(false, 0, null));

        assertThat(decision).isInstanceOfSatisfying(Gatekeeper.Decision.Wait.class,
                wait -> assertThat(wait.reason()).isEqualTo("静默时段"));
    }

    @Test
    void 临近截止提醒应绕过静默和每日额度并返回Critical() {
        var gatekeeper = new Gatekeeper(0, Duration.ofHours(1), LocalTime.of(23, 0), LocalTime.of(8, 0),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var thought = thought(0.95f, NOW.plus(Duration.ofHours(1)));

        var decision = gatekeeper.evaluate(thought, new Gatekeeper.GatekeeperContext(false, 10, Duration.ZERO));

        assertThat(decision).isInstanceOfSatisfying(Gatekeeper.Decision.Express.class,
                express -> assertThat(express.urgency()).isEqualTo(Gatekeeper.ExpressUrgency.CRITICAL));
    }

    private Thought thought(float maturity, Instant matureAt) {
        var evidence = new Evidence("memory_entity", "goal-1", null,
                "述职报告即将到期", "述职报告", NOW, 0.9f);
        return new Thought(
                "t1",
                "reminder:due_soon:goal-1",
                ThoughtKind.REMINDER,
                "述职报告即将到期",
                List.of(evidence),
                0.9f,
                maturity,
                NOW,
                matureAt,
                ThoughtState.READY,
                null,
                NOW);
    }
}
