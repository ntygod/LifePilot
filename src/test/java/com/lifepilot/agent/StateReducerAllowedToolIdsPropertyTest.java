package com.lifepilot.agent;

import com.lifepilot.agent.model.*;
import com.lifepilot.observability.guardrail.RiskLevel;
import net.jqwik.api.*;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StateReducer allowedToolIds 传递属性测试。
 *
 * <p>使用 jqwik 生成随机 AgentState（含 allowedToolIds）和随机 Action，
 * 验证 reduce() 后新 state 的 allowedToolIds 与旧 state 一致。
 * StateReducer 使用 toBuilder() 模式，allowedToolIds 应自动传递。</p>
 *
 * <p><b>Validates: Property 11, Requirements 3.6</b></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class StateReducerAllowedToolIdsPropertyTest {

    private final StateReducer reducer = new StateReducer();

    /** 工具 ID 候选池。 */
    private static final List<String> TOOL_ID_POOL = List.of(
            "builtin.todo.create", "builtin.todo.list", "builtin.schedule.create",
            "builtin.habit.track", "builtin.memory.search",
            "handoff_to_writer", "handoff_to_analyst",
            "mcp.weather.query", "mcp.search.web"
    );

    // ─────────────────────────────────────────────
    //  属性 11 — reduce() 后 allowedToolIds 保持不变
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 3.6</b>
     *
     * <p>对任意 AgentState（含随机 allowedToolIds）和任意合法 Action，
     * reduce() 后新 state 的 allowedToolIds 应与旧 state 一致。</p>
     */
    @Property(tries = 500)
    void reduce后_allowedToolIds与旧state一致(
            @ForAll("stateActionPairs") StateActionPair pair) {

        AgentState oldState = pair.state();
        Action action = pair.action();
        List<String> oldAllowedToolIds = oldState.allowedToolIds();

        AgentState newState = reducer.reduce(oldState, action);

        // allowedToolIds 应在 reduce 后保持不变
        assertEquals(oldAllowedToolIds, newState.allowedToolIds(),
                "reduce() 后 allowedToolIds 应与旧 state 一致，action=" + action.getClass().getSimpleName());
    }

    /**
     * <b>Validates: Requirements 3.6</b>
     *
     * <p>当 allowedToolIds 为 null 时，reduce() 后仍为 null。</p>
     */
    @Property(tries = 200)
    void reduce后_null的allowedToolIds保持null(
            @ForAll("stateActionPairsWithNullAllowed") StateActionPair pair) {

        AgentState oldState = pair.state();
        Action action = pair.action();

        assertNull(oldState.allowedToolIds(), "前置条件：allowedToolIds 应为 null");

        AgentState newState = reducer.reduce(oldState, action);

        assertNull(newState.allowedToolIds(),
                "reduce() 后 null 的 allowedToolIds 应保持 null，action=" + action.getClass().getSimpleName());
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成 state + 合法 action 的配对（allowedToolIds 非空）。 */
    @Provide
    Arbitrary<StateActionPair> stateActionPairs() {
        return allowedToolIdLists().flatMap(allowedToolIds ->
                phaseActionPairs().map(pa -> {
                    AgentState state = buildState(pa.phase(), allowedToolIds);
                    return new StateActionPair(state, pa.action());
                }));
    }

    /** 生成 state + 合法 action 的配对（allowedToolIds 为 null）。 */
    @Provide
    Arbitrary<StateActionPair> stateActionPairsWithNullAllowed() {
        return phaseActionPairs().map(pa -> {
            AgentState state = buildState(pa.phase(), null);
            return new StateActionPair(state, pa.action());
        });
    }

    /** 生成阶段 + 合法 action 的配对。 */
    @Provide
    Arbitrary<PhaseActionPair> phaseActionPairs() {
        return Arbitraries.oneOf(
                // UNDERSTANDING → IntentUnderstood (MODERATE → PLANNING)
                intentUnderstoodModerate().map(a -> new PhaseActionPair(AgentPhase.UNDERSTANDING, a)),
                // UNDERSTANDING → IntentUnderstood (SIMPLE → RESPONDING)
                intentUnderstoodSimple().map(a -> new PhaseActionPair(AgentPhase.UNDERSTANDING, a)),
                // UNDERSTANDING → IntentUnderstood (needsClarification → RESPONDING)
                intentUnderstoodClarification().map(a -> new PhaseActionPair(AgentPhase.UNDERSTANDING, a)),
                // PLANNING → PlanGenerated (→ EXECUTING)
                planGenerated().map(a -> new PhaseActionPair(AgentPhase.PLANNING, a)),
                // EXECUTING → ToolResult (hasMore=true → EXECUTING)
                toolResultHasMore().map(a -> new PhaseActionPair(AgentPhase.EXECUTING, a)),
                // EXECUTING → ToolResult (hasMore=false → REFLECTING)
                toolResultDone().map(a -> new PhaseActionPair(AgentPhase.EXECUTING, a)),
                // REFLECTING → ReflectionComplete (satisfied → RESPONDING)
                reflectionSatisfied().map(a -> new PhaseActionPair(AgentPhase.REFLECTING, a)),
                // REFLECTING → ReflectionComplete (needsReplanning → PLANNING)
                reflectionReplanning().map(a -> new PhaseActionPair(AgentPhase.REFLECTING, a)),
                // REFLECTING → ReflectionComplete (不满意不重规划 → EXECUTING)
                reflectionRetry().map(a -> new PhaseActionPair(AgentPhase.REFLECTING, a)),
                // RESPONDING → ResponseGenerated (→ TERMINATED)
                responseGenerated().map(a -> new PhaseActionPair(AgentPhase.RESPONDING, a)),
                // EXECUTING → BudgetExhausted (→ TERMINATED)
                budgetExhausted().map(a -> new PhaseActionPair(AgentPhase.EXECUTING, a)),
                // EXECUTING → Blocked (LOW/MEDIUM → 保持 EXECUTING)
                blockedLow().map(a -> new PhaseActionPair(AgentPhase.EXECUTING, a)),
                // EXECUTING → Blocked (CRITICAL → TERMINATED)
                blockedCritical().map(a -> new PhaseActionPair(AgentPhase.EXECUTING, a)),
                // EXECUTING → Blocked (HIGH → UNDERSTANDING)
                blockedHigh().map(a -> new PhaseActionPair(AgentPhase.EXECUTING, a)),
                // EXECUTING → ErrorRecovery (recoverable → 保持 EXECUTING)
                errorRecoverable().map(a -> new PhaseActionPair(AgentPhase.EXECUTING, a)),
                // EXECUTING → ErrorRecovery (不可恢复 → TERMINATED)
                errorUnrecoverable().map(a -> new PhaseActionPair(AgentPhase.EXECUTING, a)),
                // EXECUTING → SubAgentResult (→ REFLECTING)
                subAgentResult().map(a -> new PhaseActionPair(AgentPhase.EXECUTING, a)),
                // TERMINATED → 任意 action（吸收态，返回原 state）
                anyAction().map(a -> new PhaseActionPair(AgentPhase.TERMINATED, a))
        );
    }

    // ─────────────────────────────────────────────
    //  Action 生成器
    // ─────────────────────────────────────────────

    private Arbitrary<Action> intentUnderstoodModerate() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(20).map(summary ->
                new Action.IntentUnderstood(summary, false, null, true, List.of(), TaskComplexity.MODERATE));
    }

    private Arbitrary<Action> intentUnderstoodSimple() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(20).map(summary ->
                new Action.IntentUnderstood(summary, false, null, true, List.of(), TaskComplexity.SIMPLE));
    }

    private Arbitrary<Action> intentUnderstoodClarification() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(20).map(summary ->
                new Action.IntentUnderstood(summary, true, "请问您想要什么？", false, List.of(), TaskComplexity.MODERATE));
    }

    private Arbitrary<Action> planGenerated() {
        return Arbitraries.integers().between(100, 5000).map(tokens ->
                new Action.PlanGenerated(
                        List.of(new PlanStep(0, "builtin.todo.create", Map.of(), List.of(), "创建待办")),
                        tokens, "执行计划"));
    }

    private Arbitrary<Action> toolResultHasMore() {
        return Arbitraries.integers().between(10, 500).map(tokens ->
                new Action.ToolResult("builtin.todo.create", true, "执行成功", tokens, 100L, true));
    }

    private Arbitrary<Action> toolResultDone() {
        return Arbitraries.integers().between(10, 500).map(tokens ->
                new Action.ToolResult("builtin.todo.create", true, "执行完成", tokens, 200L, false));
    }

    private Arbitrary<Action> reflectionSatisfied() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(20).map(summary ->
                new Action.ReflectionComplete(true, null, summary, false));
    }

    private Arbitrary<Action> reflectionReplanning() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(20).map(summary ->
                new Action.ReflectionComplete(false, "调整计划", summary, true));
    }

    private Arbitrary<Action> reflectionRetry() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(20).map(summary ->
                new Action.ReflectionComplete(false, null, summary, false));
    }

    private Arbitrary<Action> responseGenerated() {
        return Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(50).map(content ->
                new Action.ResponseGenerated(content, List.of()));
    }

    private Arbitrary<Action> budgetExhausted() {
        return Arbitraries.just(new Action.BudgetExhausted("Token 预算耗尽"));
    }

    private Arbitrary<Action> blockedLow() {
        return Arbitraries.of(RiskLevel.LOW, RiskLevel.MEDIUM).map(level ->
                new Action.Blocked("风险检测", level, "some.tool"));
    }

    private Arbitrary<Action> blockedCritical() {
        return Arbitraries.just(new Action.Blocked("关键风险", RiskLevel.CRITICAL, "dangerous.tool"));
    }

    private Arbitrary<Action> blockedHigh() {
        return Arbitraries.just(new Action.Blocked("高风险操作", RiskLevel.HIGH, "risky.tool"));
    }

    private Arbitrary<Action> errorRecoverable() {
        return Arbitraries.of(AgentErrorType.values()).map(type ->
                new Action.ErrorRecovery(type, "可恢复错误", true, "重试"));
    }

    private Arbitrary<Action> errorUnrecoverable() {
        return Arbitraries.of(AgentErrorType.values()).map(type ->
                new Action.ErrorRecovery(type, "不可恢复错误", false, null));
    }

    private Arbitrary<Action> subAgentResult() {
        return Arbitraries.integers().between(50, 1000).map(tokens ->
                new Action.SubAgentResult("sub-trace-1", "writer", true, "子任务完成", tokens));
    }

    /** 生成任意 action（用于 TERMINATED 吸收态测试）。 */
    private Arbitrary<Action> anyAction() {
        return Arbitraries.oneOf(
                intentUnderstoodModerate(),
                planGenerated(),
                toolResultHasMore(),
                reflectionSatisfied(),
                responseGenerated(),
                budgetExhausted(),
                errorRecoverable(),
                subAgentResult()
        );
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 生成非空 allowedToolIds 列表。 */
    @Provide
    Arbitrary<List<String>> allowedToolIdLists() {
        return Arbitraries.of(TOOL_ID_POOL)
                .list().ofMinSize(1).ofMaxSize(6)
                .uniqueElements()
                .map(List::copyOf);
    }

    /** 构建指定阶段和 allowedToolIds 的 AgentState。 */
    private AgentState buildState(AgentPhase phase, @Nullable List<String> allowedToolIds) {
        var builder = AgentState.builder()
                .traceId("trace-" + System.nanoTime())
                .sessionId("session-1")
                .goal("测试目标")
                .phase(phase)
                .channel("cli")
                .steps(List.of())
                .stepCount(0)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(32000).tokensUsed(0).tokensReserved(0)
                        .maxSteps(20)
                        .maxDuration(Duration.ofSeconds(120))
                        .elapsed(Duration.ZERO)
                        .build())
                .parentTraceId(null)
                .depth(0)
                .done(phase == AgentPhase.TERMINATED)
                .finalOutput(null)
                .terminationReason(null)
                .allowedToolIds(allowedToolIds);
        return builder.build();
    }

    /** state + action 配对。 */
    record StateActionPair(AgentState state, Action action) {}

    /** phase + action 配对。 */
    record PhaseActionPair(AgentPhase phase, Action action) {}
}
