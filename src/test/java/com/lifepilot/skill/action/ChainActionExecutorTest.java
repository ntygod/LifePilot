package com.lifepilot.skill.action;

import com.lifepilot.agent.model.AgentState;
import com.lifepilot.skill.activation.SubAgentFactory;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SubAgentResult;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ChainActionExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
@ExtendWith(MockitoExtension.class)
class ChainActionExecutorTest {

    @Mock
    private SubAgentFactory subAgentFactory;

    @Mock
    private SkillRegistry skillRegistry;

    private VariableResolver variableResolver;
    private SkillConfigProperties config;
    private ChainActionExecutor executor;

    @BeforeEach
    void setUp() {
        variableResolver = new VariableResolver();
        config = new SkillConfigProperties();
        // 默认 maxSteps = 5
        executor = new ChainActionExecutor(subAgentFactory, skillRegistry, variableResolver, config);
    }

    @Test
    void 步骤数超过上限_返回错误() {
        // 创建 6 个步骤（上限为 5）
        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("skill-1", Map.of(), "out1"),
                new SkillAction.ChainStep("skill-2", Map.of(), "out2"),
                new SkillAction.ChainStep("skill-3", Map.of(), "out3"),
                new SkillAction.ChainStep("skill-4", Map.of(), "out4"),
                new SkillAction.ChainStep("skill-5", Map.of(), "out5"),
                new SkillAction.ChainStep("skill-6", Map.of(), "out6")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("步骤数超过上限");
        verifyNoInteractions(subAgentFactory);
    }

    @Test
    void 引用的SkillID不存在_返回错误() {
        when(skillRegistry.find("existing-skill")).thenReturn(Optional.of(mock(SkillDefinition.class)));
        when(skillRegistry.find("missing-skill")).thenReturn(Optional.empty());

        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("existing-skill", Map.of(), "out1"),
                new SkillAction.ChainStep("missing-skill", Map.of(), "out2")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("missing-skill");
        verifyNoInteractions(subAgentFactory);
    }

    @Test
    void 空步骤列表_返回成功() {
        var action = new SkillAction.ChainAction(List.of());

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEmpty();
        verifyNoInteractions(subAgentFactory);
    }

    @Test
    void 单步骤执行成功() {
        when(skillRegistry.find("translate")).thenReturn(Optional.of(mock(SkillDefinition.class)));
        when(subAgentFactory.activate(eq("translate"), anyString(), any(AgentState.class)))
                .thenReturn(SubAgentResult.builder()
                        .skillId("translate")
                        .success(true)
                        .output("翻译结果")
                        .tokensUsed(100)
                        .stepsExecuted(2)
                        .durationMs(500)
                        .traceId("trace-1")
                        .build());

        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("translate", Map.of("text", "hello"), "result1")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("翻译结果");
        assertThat(result.data()).containsEntry("tokensUsed", 100);
        verify(subAgentFactory).activate(eq("translate"), eq("hello"), any(AgentState.class));
    }

    @Test
    void 多步骤串联_前一步输出注入后一步输入() {
        when(skillRegistry.find("step-a")).thenReturn(Optional.of(mock(SkillDefinition.class)));
        when(skillRegistry.find("step-b")).thenReturn(Optional.of(mock(SkillDefinition.class)));

        // 第一步返回 "中间结果"
        when(subAgentFactory.activate(eq("step-a"), anyString(), any(AgentState.class)))
                .thenReturn(SubAgentResult.builder()
                        .skillId("step-a")
                        .success(true)
                        .output("中间结果")
                        .tokensUsed(50)
                        .stepsExecuted(1)
                        .durationMs(200)
                        .traceId("trace-a")
                        .build());

        // 第二步接收前一步输出
        when(subAgentFactory.activate(eq("step-b"), anyString(), any(AgentState.class)))
                .thenReturn(SubAgentResult.builder()
                        .skillId("step-b")
                        .success(true)
                        .output("最终结果")
                        .tokensUsed(80)
                        .stepsExecuted(2)
                        .durationMs(300)
                        .traceId("trace-b")
                        .build());

        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("step-a", Map.of("input", "原始输入"), "outputA"),
                new SkillAction.ChainStep("step-b", Map.of("data", "${result.outputA}"), "outputB")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("最终结果");
        assertThat(result.data()).containsEntry("tokensUsed", 130);

        // 验证第二步接收到了第一步的输出
        verify(subAgentFactory).activate(eq("step-b"), eq("中间结果"), any(AgentState.class));
    }

    @Test
    void 步骤失败_终止后续步骤() {
        when(skillRegistry.find("ok-skill")).thenReturn(Optional.of(mock(SkillDefinition.class)));
        when(skillRegistry.find("fail-skill")).thenReturn(Optional.of(mock(SkillDefinition.class)));
        when(skillRegistry.find("never-skill")).thenReturn(Optional.of(mock(SkillDefinition.class)));

        // 第一步成功
        when(subAgentFactory.activate(eq("ok-skill"), anyString(), any(AgentState.class)))
                .thenReturn(SubAgentResult.builder()
                        .skillId("ok-skill")
                        .success(true)
                        .output("ok")
                        .tokensUsed(30)
                        .stepsExecuted(1)
                        .durationMs(100)
                        .traceId("trace-ok")
                        .build());

        // 第二步失败
        when(subAgentFactory.activate(eq("fail-skill"), anyString(), any(AgentState.class)))
                .thenReturn(SubAgentResult.builder()
                        .skillId("fail-skill")
                        .success(false)
                        .output("执行出错")
                        .tokensUsed(20)
                        .stepsExecuted(0)
                        .durationMs(50)
                        .traceId("trace-fail")
                        .build());

        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("ok-skill", Map.of(), "out1"),
                new SkillAction.ChainStep("fail-skill", Map.of(), "out2"),
                new SkillAction.ChainStep("never-skill", Map.of(), "out3")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("步骤 1 失败");
        assertThat(result.output()).contains("fail-skill");

        // 第三步不应被执行
        verify(subAgentFactory, never()).activate(eq("never-skill"), anyString(), any(AgentState.class));
    }

    @Test
    void 累计Token消耗_正确追踪() {
        when(skillRegistry.find("s1")).thenReturn(Optional.of(mock(SkillDefinition.class)));
        when(skillRegistry.find("s2")).thenReturn(Optional.of(mock(SkillDefinition.class)));
        when(skillRegistry.find("s3")).thenReturn(Optional.of(mock(SkillDefinition.class)));

        when(subAgentFactory.activate(eq("s1"), anyString(), any(AgentState.class)))
                .thenReturn(SubAgentResult.builder()
                        .skillId("s1").success(true).output("r1")
                        .tokensUsed(100).stepsExecuted(1).durationMs(100).traceId("t1").build());
        when(subAgentFactory.activate(eq("s2"), anyString(), any(AgentState.class)))
                .thenReturn(SubAgentResult.builder()
                        .skillId("s2").success(true).output("r2")
                        .tokensUsed(200).stepsExecuted(1).durationMs(100).traceId("t2").build());
        when(subAgentFactory.activate(eq("s3"), anyString(), any(AgentState.class)))
                .thenReturn(SubAgentResult.builder()
                        .skillId("s3").success(true).output("r3")
                        .tokensUsed(300).stepsExecuted(1).durationMs(100).traceId("t3").build());

        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("s1", Map.of(), "o1"),
                new SkillAction.ChainStep("s2", Map.of(), "o2"),
                new SkillAction.ChainStep("s3", Map.of(), "o3")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("tokensUsed", 600);
    }
}
