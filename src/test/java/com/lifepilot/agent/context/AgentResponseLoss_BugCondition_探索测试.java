package com.lifepilot.agent.context;

import com.lifepilot.agent.StateReducer;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.*;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug Condition 探索测试 — 验证 BudgetExhausted 空响应 &amp; 提示词缺少日期。
 *
 * <p>在未修复代码上，这些测试预期 FAIL，确认 bug 存在：
 * <ul>
 *   <li>Test 1a/1b: reduceBudgetExhausted 返回的状态 finalOutput == null</li>
 *   <li>Test 1c/1d: buildSystemPrompt / buildUserPrompt 不包含当前日期时间</li>
 * </ul></p>
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.3, 1.4</p>
 *
 * @author zsg
 * @since 2026-03-06
 */
class AgentResponseLoss_BugCondition_探索测试 {

    private StateReducer reducer;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        reducer = new StateReducer();

        // 使用基础版构造器 + 真实 PromptRegistry（加载实际模板）
        var config = new AgentConfigProperties();
        var promptRegistry = new PromptRegistry();
        promptRegistry.register("agent/role-definition",
                new ClassPathResource("prompts/agent/role-definition.st"));
        promptRegistry.register("agent/understanding",
                new ClassPathResource("prompts/agent/understanding.st"));
        assembler = new ContextAssembler(config, promptRegistry);
    }

    // --- 辅助方法 ---

    private AgentState createStateWithSuccessfulToolStep() {
        var steps = List.of(
                new StepRecord("builtin.schedule.create", true, "日程已创建", false, 150, 200L)
        );
        return AgentState.builder()
                .traceId("trace-bug-1")
                .sessionId("session-bug-1")
                .goal("帮我创建明天下午三点的会议日程")
                .phase(AgentPhase.REFLECTING)
                .channel("cli")
                .steps(steps)
                .stepCount(5)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(1)
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

    private AgentState createStateWithEmptySteps() {
        return AgentState.builder()
                .traceId("trace-bug-2")
                .sessionId("session-bug-2")
                .goal("帮我做一个复杂任务")
                .phase(AgentPhase.UNDERSTANDING)
                .channel("cli")
                .steps(List.of())
                .stepCount(0)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(0)
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

    // --- Test 1a: BudgetExhausted 有成功工具步骤时 finalOutput 应非空 ---

    @Test
    @DisplayName("Test 1a: reduceBudgetExhausted 有成功工具步骤时 finalOutput 应不为 null 且不为空")
    void reduceBudgetExhausted_有成功工具步骤时_finalOutput应非空() {
        var state = createStateWithSuccessfulToolStep();
        var action = new Action.BudgetExhausted("迭代次数耗尽");

        var result = reducer.reduce(state, action);

        // 期望：finalOutput 不为 null 且不为空（包含工具结果摘要）
        // 未修复代码上此断言将 FAIL，确认 bug 存在
        assertThat(result.finalOutput())
                .as("BudgetExhausted 终止时 finalOutput 应不为 null（有成功工具步骤）")
                .isNotNull();
        assertThat(result.finalOutput())
                .as("BudgetExhausted 终止时 finalOutput 应不为空字符串")
                .isNotEmpty();
    }

    // --- Test 1b: BudgetExhausted 无工具步骤时 finalOutput 应非空 ---

    @Test
    @DisplayName("Test 1b: reduceBudgetExhausted 无工具步骤时 finalOutput 应不为 null")
    void reduceBudgetExhausted_无工具步骤时_finalOutput应非空() {
        var state = createStateWithEmptySteps();
        var action = new Action.BudgetExhausted("迭代次数耗尽");

        var result = reducer.reduce(state, action);

        // 期望：finalOutput 不为 null（包含友好提示）
        // 未修复代码上此断言将 FAIL，确认 bug 存在
        assertThat(result.finalOutput())
                .as("BudgetExhausted 终止时 finalOutput 应不为 null（无工具步骤）")
                .isNotNull();
    }

    // --- Test 1c: 系统提示词应包含当前日期 ---

    @Test
    @DisplayName("Test 1c: buildSystemPrompt(UNDERSTANDING) 应包含当前日期（ISO 8601 格式）")
    void buildSystemPrompt_UNDERSTANDING_应包含当前日期() {
        var systemPrompt = assembler.buildSystemPrompt(AgentPhase.UNDERSTANDING);

        // 当前日期的 ISO 8601 格式（如 2026-03-06）
        var todayISO = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        // 期望：系统提示词包含当前日期
        // 未修复代码上此断言将 FAIL，确认 bug 存在
        assertThat(systemPrompt)
                .as("系统提示词应包含当前日期（ISO 8601 格式）: %s", todayISO)
                .contains(todayISO);
    }

    // --- Test 1d: 用户提示词应包含"当前时间:"行 ---

    @Test
    @DisplayName("Test 1d: buildUserPrompt(state) 应包含「当前时间:」行")
    void buildUserPrompt_应包含当前时间行() {
        var state = createStateWithEmptySteps();

        var userPrompt = assembler.buildUserPrompt(state);

        // 期望：用户提示词包含"当前时间:"行
        // 未修复代码上此断言将 FAIL，确认 bug 存在
        assertThat(userPrompt)
                .as("用户提示词应包含「当前时间:」行")
                .contains("当前时间:");
    }
}
