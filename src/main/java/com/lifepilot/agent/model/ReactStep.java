package com.lifepilot.agent.model;

import com.lifepilot.agent.suspend.model.ResumePayload;

import java.time.Duration;
import java.time.Instant;

/**
 * ReAct 循环步骤类型 — sealed interface。
 *
 * <p>替代原 {@code Action} 的 9 种类型，简化为 6 种步骤类型：
 * <ul>
 *   <li>{@link Thought} — LLM 的推理思考</li>
 *   <li>{@link ToolCall} — 工具调用记录（由 Spring AI function calling 触发）</li>
 *   <li>{@link Observation} — 工具调用结果观察</li>
 *   <li>{@link Answer} — 最终回答（LLM 未调用工具时的纯文本输出）</li>
 *   <li>{@link Suspend} — Agent 挂起步骤（记录挂起原因和时间点）</li>
 *   <li>{@link Resume} — Agent 恢复步骤（记录恢复载荷和挂起时长）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-14
 */
public sealed interface ReactStep permits
        ReactStep.Thought,
        ReactStep.ToolCall,
        ReactStep.Observation,
        ReactStep.Answer,
        ReactStep.Suspend,
        ReactStep.Resume {

    /** LLM 的推理思考。 */
    record Thought(String content) implements ReactStep {}

    /**
     * 工具调用记录（由 Spring AI function calling 触发）。
     *
     * @param toolId    工具标识
     * @param inputJson 工具输入 JSON
     * @param latencyMs 调用耗时（毫秒）
     */
    record ToolCall(
            String toolId,
            String inputJson,
            long latencyMs
    ) implements ReactStep {}

    /**
     * 工具调用结果观察。
     *
     * @param toolId     工具标识
     * @param success    是否成功
     * @param output     工具输出内容
     * @param tokensUsed 本次调用消耗的 Token 数
     */
    record Observation(
            String toolId,
            boolean success,
            String output,
            int tokensUsed
    ) implements ReactStep {}

    /** 最终回答（LLM 未调用工具时的纯文本输出）。 */
    record Answer(String content) implements ReactStep {}

    /**
     * Agent 挂起步骤 — 记录挂起原因和时间点。
     *
     * @param reason                挂起原因
     * @param suspendedAt           挂起时间
     * @param stepIndexBeforeSuspend 挂起前的步骤索引
     */
    record Suspend(SuspendReason reason, Instant suspendedAt, int stepIndexBeforeSuspend)
            implements ReactStep {}

    /**
     * Agent 恢复步骤 — 记录恢复载荷和挂起时长。
     *
     * @param payload         恢复载荷
     * @param resumedAt       恢复时间
     * @param suspendDuration 挂起持续时长
     */
    record Resume(ResumePayload payload, Instant resumedAt, Duration suspendDuration)
            implements ReactStep {}
}
