package com.lifepilot.agent.suspend.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SuspendedAgent 序列化往返属性测试 — 验证属性 5。
 *
 * <p>对于任意有效的 ReactAgentState，调用 SuspendedAgent.from(state, mapper)
 * 构建快照后，再调用 snapshot.toAgentState(mapper) 恢复的状态应与原始 state 等价。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
class SuspendedAgent属性测试 {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * 属性 5: 序列化往返 — 核心标量字段在 from → toAgentState 往返后保持一致。
     */
    @Property(tries = 50)
    void 序列化往返_核心字段保持一致(
            @ForAll("validGoal") String goal,
            @ForAll("validChannel") String channel,
            @ForAll @IntRange(min = 0, max = 100) int stepCount,
            @ForAll @IntRange(min = 0, max = 5) int depth,
            @ForAll boolean done) {

        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        Budget budget = Budget.builder()
                .maxTokens(32000)
                .tokensUsed(1000)
                .tokensReserved(0)
                .maxSteps(20)
                .stepsUsed(5)
                .maxDuration(Duration.ofSeconds(120))
                .elapsed(Duration.ofSeconds(30))
                .build();

        ReactAgentState original = ReactAgentState.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .goal(goal)
                .channel(channel)
                .steps(List.of())
                .stepCount(stepCount)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(budget)
                .depth(depth)
                .done(done)
                .suspended(false)
                .build();

        // from → toAgentState 往返
        SuspendedAgent snapshot = SuspendedAgent.from(original, objectMapper);
        ReactAgentState restored = snapshot.toAgentState(objectMapper);

        // 核心标量字段一致性
        assertEquals(original.traceId(), restored.traceId(), "traceId 应一致");
        assertEquals(original.sessionId(), restored.sessionId(), "sessionId 应一致");
        assertEquals(original.goal(), restored.goal(), "goal 应一致");
        assertEquals(original.channel(), restored.channel(), "channel 应一致");
        assertEquals(original.stepCount(), restored.stepCount(), "stepCount 应一致");
        assertEquals(original.depth(), restored.depth(), "depth 应一致");
        assertEquals(original.done(), restored.done(), "done 应一致");
        assertEquals(original.suspended(), restored.suspended(), "suspended 应一致");
    }

    /**
     * 属性 5 补充: Budget 在往返后精确还原。
     */
    @Property(tries = 30)
    void 序列化往返_Budget精确还原(
            @ForAll @IntRange(min = 1000, max = 128000) int maxTokens,
            @ForAll @IntRange(min = 0, max = 50) int maxSteps,
            @ForAll @IntRange(min = 0, max = 300) int maxDurationSec) {

        Budget budget = Budget.builder()
                .maxTokens(maxTokens)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(maxSteps)
                .stepsUsed(0)
                .maxDuration(Duration.ofSeconds(maxDurationSec))
                .elapsed(Duration.ZERO)
                .build();

        ReactAgentState original = ReactAgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(UUID.randomUUID().toString())
                .goal("测试目标")
                .channel("cli")
                .steps(List.of())
                .stepCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(budget)
                .depth(0)
                .done(false)
                .suspended(false)
                .build();

        SuspendedAgent snapshot = SuspendedAgent.from(original, objectMapper);
        ReactAgentState restored = snapshot.toAgentState(objectMapper);

        assertNotNull(restored.budget(), "Budget 不应为 null");
        assertEquals(budget.maxTokens(), restored.budget().maxTokens(), "maxTokens 应一致");
        assertEquals(budget.maxSteps(), restored.budget().maxSteps(), "maxSteps 应一致");
        assertEquals(budget.maxDuration(), restored.budget().maxDuration(), "maxDuration 应一致");
        assertEquals(budget.tokensUsed(), restored.budget().tokensUsed(), "tokensUsed 应一致");
        assertEquals(budget.stepsUsed(), restored.budget().stepsUsed(), "stepsUsed 应一致");
    }

    /**
     * 属性 5 补充: SuspendedAgent 快照元数据正确填充。
     */
    @Property(tries = 20)
    void from构建快照_元数据正确填充(@ForAll("validGoal") String goal) {
        ReactAgentState state = ReactAgentState.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(UUID.randomUUID().toString())
                .goal(goal)
                .channel("web")
                .steps(List.of())
                .stepCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(32000).tokensUsed(0).tokensReserved(0)
                        .maxSteps(20).stepsUsed(0)
                        .maxDuration(Duration.ofSeconds(120)).elapsed(Duration.ZERO)
                        .build())
                .depth(0)
                .done(false)
                .suspended(false)
                .build();

        SuspendedAgent snapshot = SuspendedAgent.from(state, objectMapper);

        assertEquals(state.traceId(), snapshot.traceId(), "traceId 应一致");
        assertEquals(state.sessionId(), snapshot.sessionId(), "sessionId 应一致");
        assertEquals(state.channel(), snapshot.channel(), "channel 应一致");
        assertNotNull(snapshot.stateJson(), "stateJson 不应为 null");
        assertFalse(snapshot.stateJson().isBlank(), "stateJson 不应为空");
        assertNotNull(snapshot.suspendedAt(), "suspendedAt 不应为 null");
    }

    // ---- Arbitrary 提供器 ----

    @Provide
    Arbitrary<String> validGoal() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(200)
                .alpha();
    }

    @Provide
    Arbitrary<String> validChannel() {
        return Arbitraries.of("cli", "web", "feishu", "api");
    }
}
