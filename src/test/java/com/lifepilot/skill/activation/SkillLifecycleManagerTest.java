package com.lifepilot.skill.activation;

import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.skill.event.SkillLifecycleEvent;
import com.lifepilot.skill.model.SubAgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SkillLifecycleManager 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
@ExtendWith(MockitoExtension.class)
class SkillLifecycleManagerTest {

    @Mock
    private SubAgentFactory subAgentFactory;
    @Mock
    private SkillMetricsTracker metricsTracker;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SkillLifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        lifecycleManager = new SkillLifecycleManager(5, subAgentFactory, metricsTracker, eventPublisher);
    }

    @Test
    void activate_成功执行返回SubAgentResult() {
        SubAgentResult expected = createSuccessResult("todo");
        when(subAgentFactory.activate(eq("todo"), eq("创建待办"), any(AgentState.class)))
                .thenReturn(expected);

        AgentState parentState = createParentState();

        SubAgentResult result = lifecycleManager.activate("todo", "创建待办", parentState);

        assertThat(result.success()).isTrue();
        assertThat(result.skillId()).isEqualTo("todo");
        assertThat(result.output()).isEqualTo("待办已创建");
    }

    @Test
    void activate_并发超限返回失败结果() {
        // maxConcurrentActivations = 1
        SkillLifecycleManager manager = new SkillLifecycleManager(
                1, subAgentFactory, metricsTracker, eventPublisher);

        // 模拟第一个激活阻塞
        SubAgentResult blockingResult = createSuccessResult("todo");
        when(subAgentFactory.activate(eq("todo"), any(), any()))
                .thenAnswer(invocation -> {
                    // 在第一个激活执行期间，尝试第二个激活
                    // 此时 activeCount 已经是 1
                    return blockingResult;
                });

        AgentState parentState = createParentState();

        // 第一个激活成功
        SubAgentResult result1 = manager.activate("todo", "第一个", parentState);
        assertThat(result1.success()).isTrue();

        // activeCount 已恢复为 0，所以需要用不同方式测试并发限制
        // 使用 maxConcurrentActivations = 0 来测试
        SkillLifecycleManager zeroManager = new SkillLifecycleManager(
                0, subAgentFactory, metricsTracker, eventPublisher);

        SubAgentResult result2 = zeroManager.activate("todo", "超限", parentState);
        assertThat(result2.success()).isFalse();
        assertThat(result2.output()).contains("并发激活超限");
    }

    @Test
    void activate_发布Activated和Deactivated事件() {
        SubAgentResult expected = createSuccessResult("todo");
        when(subAgentFactory.activate(eq("todo"), any(), any())).thenReturn(expected);

        AgentState parentState = createParentState();
        lifecycleManager.activate("todo", "创建待办", parentState);

        // 验证发布了两个事件
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

        List<Object> events = eventCaptor.getAllValues();
        assertThat(events.get(0)).isInstanceOf(SkillLifecycleEvent.Activated.class);
        assertThat(events.get(1)).isInstanceOf(SkillLifecycleEvent.Deactivated.class);

        SkillLifecycleEvent.Activated activated = (SkillLifecycleEvent.Activated) events.get(0);
        assertThat(activated.skillId()).isEqualTo("todo");

        SkillLifecycleEvent.Deactivated deactivated = (SkillLifecycleEvent.Deactivated) events.get(1);
        assertThat(deactivated.skillId()).isEqualTo("todo");
        assertThat(deactivated.result()).isEqualTo(expected);
    }

    @Test
    void activate_记录指标() {
        SubAgentResult expected = createSuccessResult("todo");
        when(subAgentFactory.activate(eq("todo"), any(), any())).thenReturn(expected);

        AgentState parentState = createParentState();
        lifecycleManager.activate("todo", "创建待办", parentState);

        verify(metricsTracker).record("todo", expected);
    }

    @Test
    void activate_SubAgentFactory抛出SkillActivationException时返回失败结果() {
        when(subAgentFactory.activate(eq("non-existent"), any(), any()))
                .thenThrow(new SkillActivationException("Skill 不存在: non-existent"));

        AgentState parentState = createParentState();

        SubAgentResult result = lifecycleManager.activate("non-existent", "测试", parentState);

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("Skill 激活失败");
        assertThat(result.skillId()).isEqualTo("non-existent");
    }

    @Test
    void activate_异常后仍递减活跃计数() {
        when(subAgentFactory.activate(eq("error-skill"), any(), any()))
                .thenThrow(new SkillActivationException("测试异常"));

        AgentState parentState = createParentState();
        lifecycleManager.activate("error-skill", "测试", parentState);

        // 活跃计数应恢复为 0
        assertThat(lifecycleManager.getActiveCount()).isEqualTo(0);
    }

    @Test
    void activate_异常后仍记录指标和发布Deactivated事件() {
        when(subAgentFactory.activate(eq("error-skill"), any(), any()))
                .thenThrow(new SkillActivationException("测试异常"));

        AgentState parentState = createParentState();
        SubAgentResult result = lifecycleManager.activate("error-skill", "测试", parentState);

        // 验证记录了指标
        verify(metricsTracker).record(eq("error-skill"), any(SubAgentResult.class));

        // 验证发布了 Activated 和 Deactivated 事件
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

        List<Object> events = eventCaptor.getAllValues();
        assertThat(events.get(0)).isInstanceOf(SkillLifecycleEvent.Activated.class);
        assertThat(events.get(1)).isInstanceOf(SkillLifecycleEvent.Deactivated.class);
    }

    @Test
    void activate_并发超限时不递增活跃计数() {
        SkillLifecycleManager zeroManager = new SkillLifecycleManager(
                0, subAgentFactory, metricsTracker, eventPublisher);

        AgentState parentState = createParentState();
        zeroManager.activate("todo", "超限", parentState);

        assertThat(zeroManager.getActiveCount()).isEqualTo(0);
    }

    @Test
    void activate_并发超限时不发布事件() {
        SkillLifecycleManager zeroManager = new SkillLifecycleManager(
                0, subAgentFactory, metricsTracker, eventPublisher);

        AgentState parentState = createParentState();
        zeroManager.activate("todo", "超限", parentState);

        verify(eventPublisher, never()).publishEvent(any(SkillLifecycleEvent.class));
    }

    @Test
    void getActiveCount_初始为零() {
        assertThat(lifecycleManager.getActiveCount()).isEqualTo(0);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private AgentState createParentState() {
        return AgentState.builder()
                .traceId("parent-trace")
                .sessionId("session-1")
                .goal("父目标")
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

    private SubAgentResult createSuccessResult(String skillId) {
        return SubAgentResult.builder()
                .skillId(skillId)
                .success(true)
                .output("待办已创建")
                .terminationReason(null)
                .tokensUsed(200)
                .stepsExecuted(5)
                .durationMs(1500)
                .traceId("parent-trace/sub-" + skillId + "-abc12345")
                .build();
    }
}
