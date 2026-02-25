package com.lifepilot.skill.activation;

import com.lifepilot.agent.model.AgentState;
import com.lifepilot.skill.event.SkillLifecycleEvent;
import com.lifepilot.skill.model.SubAgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Skill 生命周期管理器 — 协调并发控制、激活、停用和指标追踪。
 *
 * <p>核心职责：
 * <ul>
 *   <li>通过 {@link AtomicInteger} 控制最大并发激活数</li>
 *   <li>委托 {@link SubAgentFactory} 执行实际激活</li>
 *   <li>发布 {@link SkillLifecycleEvent} 生命周期事件</li>
 *   <li>通过 {@link SkillMetricsTracker} 记录激活指标</li>
 * </ul>
 *
 * <p>并发超限时返回 success=false 的 {@link SubAgentResult}，不抛出异常。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SkillLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(SkillLifecycleManager.class);

    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final int maxConcurrentActivations;
    private final SubAgentFactory subAgentFactory;
    private final SkillMetricsTracker metricsTracker;
    private final ApplicationEventPublisher eventPublisher;

    public SkillLifecycleManager(int maxConcurrentActivations,
                                 SubAgentFactory subAgentFactory,
                                 SkillMetricsTracker metricsTracker,
                                 ApplicationEventPublisher eventPublisher) {
        this.maxConcurrentActivations = maxConcurrentActivations;
        this.subAgentFactory = subAgentFactory;
        this.metricsTracker = metricsTracker;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 激活 Skill，检查并发限制后委托给 SubAgentFactory。
     *
     * <p>执行流程：
     * <ol>
     *   <li>检查并发限制，超限返回 success=false 的 SubAgentResult</li>
     *   <li>递增活跃计数</li>
     *   <li>发布 {@link SkillLifecycleEvent.Activated} 事件</li>
     *   <li>委托 {@link SubAgentFactory#activate} 执行</li>
     *   <li>在 finally 块中：递减活跃计数、记录指标、发布 Deactivated 事件</li>
     * </ol>
     *
     * @param skillId     Skill ID
     * @param input       用户输入
     * @param parentState 父 Agent 状态
     * @return SubAgent 执行结果
     */
    public SubAgentResult activate(String skillId, String input, AgentState parentState) {
        // 1. 检查并发限制
        if (activeCount.get() >= maxConcurrentActivations) {
            log.warn("Skill 并发激活超限: skillId={}, activeCount={}, maxConcurrent={}",
                    skillId, activeCount.get(), maxConcurrentActivations);
            return SubAgentResult.builder()
                    .skillId(skillId)
                    .success(false)
                    .output("Skill 并发激活超限，当前活跃数: " + activeCount.get())
                    .terminationReason("并发激活超限")
                    .tokensUsed(0)
                    .stepsExecuted(0)
                    .durationMs(0)
                    .traceId(parentState.traceId() + "/sub-" + skillId)
                    .build();
        }

        // 2. 递增活跃计数
        activeCount.incrementAndGet();

        // 3. 生成临时 traceId 用于 Activated 事件
        String activatedTraceId = parentState.traceId() + "/sub-" + skillId + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        // 4. 发布 Activated 事件
        eventPublisher.publishEvent(new SkillLifecycleEvent.Activated(skillId, activatedTraceId));

        SubAgentResult result = null;
        try {
            // 5. 委托 SubAgentFactory 执行
            result = subAgentFactory.activate(skillId, input, parentState);
        } catch (SkillActivationException e) {
            // SubAgentFactory 抛出的激活异常（Skill 不存在、深度超限）
            log.warn("Skill 激活失败: skillId={}, error={}", skillId, e.getMessage());
            result = SubAgentResult.builder()
                    .skillId(skillId)
                    .success(false)
                    .output("Skill 激活失败: " + e.getMessage())
                    .terminationReason(e.getMessage())
                    .tokensUsed(0)
                    .stepsExecuted(0)
                    .durationMs(0)
                    .traceId(activatedTraceId)
                    .build();
        } finally {
            // 6. 递减活跃计数
            activeCount.decrementAndGet();

            // 7. 记录指标
            if (result != null) {
                metricsTracker.record(skillId, result);
            }

            // 8. 发布 Deactivated 事件，使用 result 中的 traceId
            if (result != null) {
                eventPublisher.publishEvent(
                        new SkillLifecycleEvent.Deactivated(skillId, result.traceId(), result));
            }
        }

        return result;
    }

    /**
     * 获取当前活跃的 Skill 激活数。
     *
     * @return 当前活跃数
     */
    public int getActiveCount() {
        return activeCount.get();
    }
}
