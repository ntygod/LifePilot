package com.lifepilot.skill.activation;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.skill.memory.MemoryAccessEnforcer;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SubAgentFactory 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
@ExtendWith(MockitoExtension.class)
class SubAgentFactoryTest {

    @Mock
    private SkillRegistry skillRegistry;
    @Mock
    private AgentLoop agentLoop;
    @Mock
    private DynamicToolRegistry toolRegistry;
    @Mock
    private MemoryAccessEnforcer memoryAccessEnforcer;

    private SubAgentFactory subAgentFactory;

    @BeforeEach
    void setUp() {
        subAgentFactory = new SubAgentFactory(skillRegistry, agentLoop, toolRegistry, memoryAccessEnforcer);
    }

    @Test
    void activate_Skill不存在时抛出SkillActivationException() {
        when(skillRegistry.find("non-existent")).thenReturn(Optional.empty());

        AgentState parentState = createParentState(0);

        assertThatThrownBy(() -> subAgentFactory.activate("non-existent", "测试输入", parentState))
                .isInstanceOf(SkillActivationException.class)
                .hasMessageContaining("Skill 不存在");
    }

    @Test
    void activate_深度超限时抛出SkillActivationException() {
        SkillDefinition skill = createTestSkill("test-skill");
        when(skillRegistry.find("test-skill")).thenReturn(Optional.of(skill));

        // depth=2，+1=3 > MAX_ACTIVATION_DEPTH(2)
        AgentState parentState = createParentState(2);

        assertThatThrownBy(() -> subAgentFactory.activate("test-skill", "测试输入", parentState))
                .isInstanceOf(SkillActivationException.class)
                .hasMessageContaining("深度超过限制");
    }

    @Test
    void activate_深度等于限制时抛出SkillActivationException() {
        SkillDefinition skill = createTestSkill("test-skill");
        when(skillRegistry.find("test-skill")).thenReturn(Optional.of(skill));

        // depth=2，+1=3 > MAX_ACTIVATION_DEPTH(2)
        AgentState parentState = createParentState(2);

        assertThatThrownBy(() -> subAgentFactory.activate("test-skill", "测试输入", parentState))
                .isInstanceOf(SkillActivationException.class);
    }

    @Test
    void activate_深度为1时允许激活() {
        SkillDefinition skill = createTestSkill("test-skill");
        when(skillRegistry.find("test-skill")).thenReturn(Optional.of(skill));

        AgentResponse mockResponse = new AgentResponse(
                "trace-123", "session-1", "执行结果", 100, 3, null);
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(mockResponse);

        // depth=1，+1=2 <= MAX_ACTIVATION_DEPTH(2)
        AgentState parentState = createParentState(1);

        SubAgentResult result = subAgentFactory.activate("test-skill", "测试输入", parentState);

        assertThat(result.success()).isTrue();
        assertThat(result.skillId()).isEqualTo("test-skill");
        assertThat(result.output()).isEqualTo("执行结果");
        assertThat(result.tokensUsed()).isEqualTo(100);
        assertThat(result.stepsExecuted()).isEqualTo(3);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void activate_成功执行返回正确结果() {
        SkillDefinition skill = createTestSkill("todo");
        when(skillRegistry.find("todo")).thenReturn(Optional.of(skill));

        AgentResponse mockResponse = new AgentResponse(
                "trace-456", "session-1", "待办已创建", 200, 5, "正常完成");
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(mockResponse);

        AgentState parentState = createParentState(0);

        SubAgentResult result = subAgentFactory.activate("todo", "创建待办", parentState);

        assertThat(result.success()).isTrue();
        assertThat(result.skillId()).isEqualTo("todo");
        assertThat(result.output()).isEqualTo("待办已创建");
        assertThat(result.terminationReason()).isEqualTo("正常完成");
        assertThat(result.tokensUsed()).isEqualTo(200);
        assertThat(result.stepsExecuted()).isEqualTo(5);
        assertThat(result.traceId()).startsWith("parent-trace/sub-todo-");
    }

    @Test
    void activate_AgentLoop异常时返回失败结果() {
        SkillDefinition skill = createTestSkill("test-skill");
        when(skillRegistry.find("test-skill")).thenReturn(Optional.of(skill));
        when(agentLoop.run(any(AgentRequest.class))).thenThrow(new RuntimeException("LLM 不可用"));

        AgentState parentState = createParentState(0);

        SubAgentResult result = subAgentFactory.activate("test-skill", "测试输入", parentState);

        assertThat(result.success()).isFalse();
        assertThat(result.skillId()).isEqualTo("test-skill");
        assertThat(result.output()).contains("Skill 执行失败");
        assertThat(result.terminationReason()).contains("RuntimeException");
        assertThat(result.tokensUsed()).isEqualTo(0);
        assertThat(result.stepsExecuted()).isEqualTo(0);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void activate_traceId格式正确() {
        SkillDefinition skill = createTestSkill("schedule");
        when(skillRegistry.find("schedule")).thenReturn(Optional.of(skill));

        AgentResponse mockResponse = new AgentResponse(
                "trace-789", "session-1", "日程已创建", 150, 4, null);
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(mockResponse);

        AgentState parentState = createParentState(0);

        SubAgentResult result = subAgentFactory.activate("schedule", "创建日程", parentState);

        // traceId 格式：parentTraceId/sub-skillId-randomSuffix
        assertThat(result.traceId()).startsWith("parent-trace/sub-schedule-");
        // randomSuffix 为 8 位
        String suffix = result.traceId().substring("parent-trace/sub-schedule-".length());
        assertThat(suffix).hasSize(8);
    }

    @Test
    void activate_记录执行时间() {
        SkillDefinition skill = createTestSkill("test-skill");
        when(skillRegistry.find("test-skill")).thenReturn(Optional.of(skill));

        AgentResponse mockResponse = new AgentResponse(
                "trace-123", "session-1", "结果", 50, 2, null);
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(mockResponse);

        AgentState parentState = createParentState(0);

        SubAgentResult result = subAgentFactory.activate("test-skill", "测试", parentState);

        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private AgentState createParentState(int depth) {
        return AgentState.builder()
                .traceId("parent-trace")
                .sessionId("session-1")
                .goal("父目标")
                .phase(com.lifepilot.agent.model.AgentPhase.UNDERSTANDING)
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
                .depth(depth)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .build();
    }

    private SkillDefinition createTestSkill(String id) {
        return SkillDefinition.builder()
                .id(id)
                .name("测试 Skill")
                .description("测试用 Skill")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .systemPrompt("你是一个测试助手")
                .allowedTools(List.of("builtin.test.tool"))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
                .metadata(Map.of())
                .preferredProviderId(null)
                .build();
    }
}
