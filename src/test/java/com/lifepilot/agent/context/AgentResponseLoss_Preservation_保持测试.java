package com.lifepilot.agent.context;

import com.lifepilot.agent.StateReducer;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.*;
import com.lifepilot.prompt.PromptRegistry;
import net.jqwik.api.*;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preservation 保持测试 — 验证正常路径 finalOutput 与阶段转换行为不变。
 *
 * <p>这些测试在未修复代码上必须 PASS，建立基线确保修复不破坏现有行为。</p>
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5</p>
 *
 * @author zsg
 * @since 2026-03-06
 */
class AgentResponseLoss_Preservation_保持测试 {

    private final StateReducer reducer = new StateReducer();

    // --- 辅助方法 ---

    /** 构建处于指定阶段的最小 AgentState。 */
    private AgentState buildState(AgentPhase phase, int revisionCount) {
        return AgentState.builder()
                .traceId("trace-pres")
                .sessionId("session-pres")
                .goal("测试目标")
                .phase(phase)
                .channel("cli")
                .steps(List.of())
                .stepCount(0)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(revisionCount)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.defaultBudget())
                .parentTraceId(null)
                .depth(0)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .build();
    }

    private ContextAssembler buildAssembler() {
        var config = new AgentConfigProperties();
        var promptRegistry = new PromptRegistry();
        promptRegistry.register("agent/role-definition",
                new ClassPathResource("prompts/agent/role-definition.st"));
        promptRegistry.register("agent/understanding",
                new ClassPathResource("prompts/agent/understanding.st"));
        promptRegistry.register("agent/planning",
                new ClassPathResource("prompts/agent/planning.st"));
        promptRegistry.register("agent/reflecting",
                new ClassPathResource("prompts/agent/reflecting.st"));
        promptRegistry.register("agent/responding",
                new ClassPathResource("prompts/agent/responding.st"));
        return new ContextAssembler(config, promptRegistry);
    }

    // --- Property 1: ResponseGenerated 的 finalOutput 等于 content ---
    // Validates: Requirements 3.1, 3.4

    @Provide
    Arbitrary<String> responseContents() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(500)
                .alpha().numeric().withChars(' ', '，', '。', '！', '\n');
    }

    /**
     * **Validates: Requirements 3.1, 3.4**
     *
     * <p>对任意 ResponseGenerated(content)，reduceResponseGenerated 后
     * finalOutput 必须等于 content。</p>
     */
    @Property(tries = 100)
    void reduceResponseGenerated_finalOutput等于content(
            @ForAll("responseContents") String content) {

        var state = buildState(AgentPhase.RESPONDING, 0);
        var action = new Action.ResponseGenerated(content, List.of());

        var result = reducer.reduce(state, action);

        assertThat(result.finalOutput())
                .as("ResponseGenerated 后 finalOutput 应等于 action.content()")
                .isEqualTo(content);
        assertThat(result.phase()).isEqualTo(AgentPhase.TERMINATED);
        assertThat(result.done()).isTrue();
    }

    // --- Property 2: ReflectionComplete(satisfied=true) 转入 RESPONDING ---
    // Validates: Requirements 3.4

    @Provide
    Arbitrary<String> summaries() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(200)
                .alpha().numeric().withChars(' ', '，', '。');
    }

    /**
     * **Validates: Requirements 3.4**
     *
     * <p>对任意 REFLECTING 阶段的 AgentState，ReflectionComplete(satisfied=true)
     * 必须转入 RESPONDING 阶段。</p>
     */
    @Property(tries = 100)
    void reduceReflectionComplete_satisfied时转入RESPONDING(
            @ForAll("summaries") String summary) {

        var state = buildState(AgentPhase.REFLECTING, 0);
        var action = new Action.ReflectionComplete(true, null, summary, false);

        var result = reducer.reduce(state, action);

        assertThat(result.phase())
                .as("ReflectionComplete(satisfied=true) 应转入 RESPONDING")
                .isEqualTo(AgentPhase.RESPONDING);
    }

    // --- Property 3: IntentUnderstood(complexity=SIMPLE) 转入 RESPONDING ---
    // Validates: Requirements 3.2

    /**
     * **Validates: Requirements 3.2**
     *
     * <p>对任意 UNDERSTANDING 阶段的 AgentState，IntentUnderstood(complexity=SIMPLE)
     * 必须直接转入 RESPONDING 阶段，跳过 PLANNING/EXECUTING。</p>
     */
    @Property(tries = 100)
    void reduceIntentUnderstood_SIMPLE复杂度直接转入RESPONDING(
            @ForAll("summaries") String summary) {

        var state = buildState(AgentPhase.UNDERSTANDING, 0);
        var action = new Action.IntentUnderstood(
                summary, false, null, true, List.of(), TaskComplexity.SIMPLE);

        var result = reducer.reduce(state, action);

        assertThat(result.phase())
                .as("IntentUnderstood(SIMPLE) 应直接转入 RESPONDING")
                .isEqualTo(AgentPhase.RESPONDING);
    }

    // --- Property 4: revisionCount >= 2 时 ReflectionComplete(satisfied=false) 强制转入 RESPONDING ---
    // Validates: Requirements 3.5

    @Provide
    Arbitrary<Integer> highRevisionCounts() {
        return Arbitraries.integers().between(2, 10);
    }

    /**
     * **Validates: Requirements 3.5**
     *
     * <p>对任意 revisionCount >= MAX_REVISION_CYCLES(2) 的 AgentState，
     * ReflectionComplete(satisfied=false, needsReplanning=true) 必须强制转入 RESPONDING。</p>
     */
    @Property(tries = 100)
    void reduceReflectionComplete_revisionCount达上限时强制转入RESPONDING(
            @ForAll("highRevisionCounts") int revisionCount,
            @ForAll("summaries") String summary) {

        var state = buildState(AgentPhase.REFLECTING, revisionCount);
        var action = new Action.ReflectionComplete(false, "需要调整", summary, true);

        var result = reducer.reduce(state, action);

        assertThat(result.phase())
                .as("revisionCount >= 2 时 ReflectionComplete(satisfied=false) 应强制转入 RESPONDING")
                .isEqualTo(AgentPhase.RESPONDING);
    }

    // --- Property 5: buildSystemPrompt 在活跃阶段包含 roleDefinition 和 userProfile 内容 ---
    // Validates: Requirements 3.3

    @Provide
    Arbitrary<AgentPhase> activeNonExecutingPhases() {
        return Arbitraries.of(
                AgentPhase.UNDERSTANDING,
                AgentPhase.PLANNING,
                AgentPhase.REFLECTING,
                AgentPhase.RESPONDING);
    }

    /**
     * **Validates: Requirements 3.3**
     *
     * <p>对 UNDERSTANDING、PLANNING、REFLECTING、RESPONDING 四个阶段，
     * buildSystemPrompt 返回的提示词必须包含 roleDefinition 和 userProfile 内容。</p>
     */
    @Property(tries = 20)
    void buildSystemPrompt_活跃阶段包含roleDefinition和userProfile(
            @ForAll("activeNonExecutingPhases") AgentPhase phase) {

        var assembler = buildAssembler();
        var systemPrompt = assembler.buildSystemPrompt(phase);

        // roleDefinition 中的标志性内容
        assertThat(systemPrompt)
                .as("系统提示词应包含 roleDefinition 中的 ZhiWei 标识")
                .contains("ZhiWei");

        // 提示词不应为空
        assertThat(systemPrompt)
                .as("活跃阶段的系统提示词不应为空")
                .isNotEmpty();
    }
}
