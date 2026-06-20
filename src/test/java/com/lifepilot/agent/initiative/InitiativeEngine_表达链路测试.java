package com.lifepilot.agent.initiative;

import com.lifepilot.agent.initiative.express.ConversationInitiator;
import com.lifepilot.agent.initiative.gate.Gatekeeper;
import com.lifepilot.agent.initiative.maturity.MaturityModel;
import com.lifepilot.agent.initiative.model.Evidence;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtKind;
import com.lifepilot.agent.initiative.model.ThoughtState;
import com.lifepilot.agent.initiative.pool.ThoughtPool;
import com.lifepilot.agent.initiative.pool.ThoughtRepository;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InitiativeEngine 表达链路测试。
 *
 * @author zsg
 * @since 2026-06-20
 */
class InitiativeEngine_表达链路测试 {

    private static final Instant NOW = Instant.parse("2026-06-20T00:00:00Z");
    private static final MaturityModel MATURITY_MODEL = new MaturityModel(
            new MaturityModel.Config(0.6f, 0.5f, 0.15f, 0.15f, 48.0, 24.0, 72.0));

    private ThoughtPool pool() {
        var repository = mock(ThoughtRepository.class);
        when(repository.findByState(any(ThoughtState.class))).thenReturn(List.of());
        return new ThoughtPool(10, Duration.ofHours(72), Duration.ofHours(48), repository, MATURITY_MODEL);
    }

    private Gatekeeper gatekeeper() {
        return new Gatekeeper(10, Duration.ZERO, LocalTime.of(1, 0), LocalTime.of(2, 0),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Thought readyReminder(String id) {
        var evidence = new Evidence(
                "memory_entity",
                "goal-1",
                null,
                "述职报告下周到期",
                "述职报告",
                NOW,
                0.9f);
        return new Thought(
                id,
                "reminder:due_soon:goal-1",
                ThoughtKind.REMINDER,
                "述职报告下周到期",
                List.of(evidence),
                0.9f,
                0.95f,
                NOW,
                NOW.plus(Duration.ofHours(1)),
                ThoughtState.READY,
                null,
                NOW);
    }

    @Test
    void 门控通过后发起主动对话并记录会话ID() {
        var pool = pool();
        pool.submit(readyReminder("t1"));
        var thinker = mock(Thinker.class);
        var orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.run(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("trace-1", "initiative-session", "已发起", 0, 0, null));
        var engine = new InitiativeEngine(pool, gatekeeper(), thinker, new ConversationInitiator(orchestrator));

        var expressed = engine.tryExpress(new Gatekeeper.GatekeeperContext(false, 0, null));

        assertThat(expressed).isNotNull();
        assertThat(expressed.state()).isEqualTo(ThoughtState.EXPRESSED);
        assertThat(expressed.conversationId()).isEqualTo("initiative-session");
        assertThat(pool.activeCount()).isZero();
        assertThat(pool.findById("t1")).hasValueSatisfying(t -> {
            assertThat(t.state()).isEqualTo(ThoughtState.EXPRESSED);
            assertThat(t.conversationId()).isEqualTo("initiative-session");
        });

        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(orchestrator).run(requestCaptor.capture());
        var request = requestCaptor.getValue();
        assertThat(request.message()).contains("述职报告下周到期");
        assertThat(request.systemPrompt()).contains("你正在发起一次主动对话", "证据 1");
        assertThat(request.source().sourceId()).isEqualTo("initiative:t1");
    }

    @Test
    void 发起主动对话失败时保留就绪状态() {
        var pool = pool();
        pool.submit(readyReminder("t1"));
        var thinker = mock(Thinker.class);
        var orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.run(any(AgentRequest.class))).thenThrow(new IllegalStateException("模型不可用"));
        var engine = new InitiativeEngine(pool, gatekeeper(), thinker, new ConversationInitiator(orchestrator));

        var expressed = engine.tryExpress(new Gatekeeper.GatekeeperContext(false, 0, null));

        assertThat(expressed).isNull();
        assertThat(pool.findById("t1")).hasValueSatisfying(t -> {
            assertThat(t.state()).isEqualTo(ThoughtState.READY);
            assertThat(t.conversationId()).isNull();
        });
    }
}
