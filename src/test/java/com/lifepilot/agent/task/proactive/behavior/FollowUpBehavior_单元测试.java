package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.proactive.*;
import com.lifepilot.agent.task.proactive.intent.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowUpBehavior_单元测试 {

    IntentMemoryService intentMemoryService;
    FollowUpBehavior behavior;

    @BeforeEach
    void setUp() {
        intentMemoryService = mock(IntentMemoryService.class);
        behavior = new FollowUpBehavior(intentMemoryService, null, null);
    }

    @Test
    void 插件名称() {
        assertThat(behavior.name()).isEqualTo("follow-up");
    }

    @Test
    void detect_有活跃意图时返回候选() {
        var intent = new IntentRecord("i1", "u1", IntentType.GOAL, "买耳机",
                null, "sess-1", IntentStatus.ACTIVE, 2,
                Instant.now().minusSeconds(3 * 86400), Instant.now().plusSeconds(87 * 86400),
                null, null, Instant.now().minusSeconds(86400));
        when(intentMemoryService.getActiveIntents("u1")).thenReturn(List.of(intent));

        var candidates = behavior.detect(testCtx());

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().behaviorName()).isEqualTo("follow-up");
    }

    @Test
    void detect_无意图时返回空() {
        when(intentMemoryService.getActiveIntents("u1")).thenReturn(List.of());
        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void detect_刚创建的意图不追问() {
        var intent = new IntentRecord("i1", "u1", IntentType.GOAL, "买耳机",
                null, "sess-1", IntentStatus.ACTIVE, 0,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(90 * 86400),
                null, null, Instant.now().minusSeconds(3600));
        when(intentMemoryService.getActiveIntents("u1")).thenReturn(List.of(intent));

        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void detect_检查次数过多不追问() {
        var intent = new IntentRecord("i1", "u1", IntentType.GOAL, "买耳机",
                null, "sess-1", IntentStatus.ACTIVE, 5,
                Instant.now().minusSeconds(10 * 86400), Instant.now().plusSeconds(80 * 86400),
                null, null, Instant.now().minusSeconds(86400));
        when(intentMemoryService.getActiveIntents("u1")).thenReturn(List.of(intent));

        assertThat(behavior.detect(testCtx())).isEmpty();
    }

    @Test
    void reason_使用回退模板生成追问() {
        var candidate = new ProactiveCandidate("c1", "follow-up", "intent-i1",
                "买耳机", 0.5f, "活跃意图: GOAL",
                new IntentRecord("i1", "u1", IntentType.GOAL, "买耳机",
                        null, null, IntentStatus.ACTIVE, 1,
                        Instant.now().minusSeconds(3 * 86400), null, null, null, Instant.now()));

        var actions = behavior.reason(List.of(candidate), testCtx());

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().content()).contains("买耳机");
        assertThat(actions.getFirst().suggestedLevel()).isEqualTo(DeliveryLevel.NOTIFY);
    }

    @Test
    void reason_递增检查计数() {
        var intent = new IntentRecord("i1", "u1", IntentType.GOAL, "买耳机",
                null, null, IntentStatus.ACTIVE, 1,
                Instant.now().minusSeconds(3 * 86400), null, null, null, Instant.now());
        var candidate = new ProactiveCandidate("c1", "follow-up", "intent-i1",
                "买耳机", 0.5f, "活跃意图", intent);

        behavior.reason(List.of(candidate), testCtx());

        verify(intentMemoryService).incrementCheckCount("i1");
    }

    private ContextPacket testCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30);
    }
}
