package com.lifepilot.agent.model;

import com.lifepilot.agent.suspend.model.ResumePayload;

import java.time.Duration;
import java.time.Instant;

/**
 * ReAct 循环步骤类型 — sealed interface。
 *
 * <p>替代原 {@code Action} 的 9 种类型，简化为 8 种步骤类型：
 * <ul>
 *   <li>{@link Progress} — 面向用户展示的执行进度提示，不参与模型上下文回放</li>
 *   <li>{@link Thought} — LLM 的推理思考</li>
 *   <li>{@link ToolCall} — 工具调用记录（由 Spring AI function calling 触发）</li>
 *   <li>{@link Observation} — 工具调用结果观察</li>
 *   <li>{@link Answer} — 最终回答（LLM 未调用工具时的纯文本输出）</li>
 *   <li>{@link Suspend} — Agent 挂起步骤（记录挂起原因和时间点）</li>
 *   <li>{@link Resume} — Agent 恢复步骤（记录恢复载荷和挂起时长）</li>
 *   <li>{@link Reflect} — 执行回顾步骤（系统在特定触发点注入，促使 LLM 评估执行进展并调整策略）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-14
 */
public sealed interface ReactStep permits
        ReactStep.Progress,
        ReactStep.Thought,
        ReactStep.ToolCall,
        ReactStep.Observation,
        ReactStep.Answer,
        ReactStep.Suspend,
        ReactStep.Resume,
        ReactStep.Reflect {

    /** 面向用户的阶段进度提示，不应重新喂给模型。 */
    record Progress(String content) implements ReactStep {}

    /** LLM 的推理思考。 */
    record Thought(String content) implements ReactStep {}

    /**
     * 工具调用记录（由 Spring AI function calling 触发）。
     *
     * @param toolId    工具标识（技术 ID，如 todo.create）
     * @param toolName  工具显示名称（用户可读，如 "创建待办"），为 null 时前端回退到 toolId
     * @param inputJson 工具输入 JSON
     * @param latencyMs 调用耗时（毫秒）
     * @param callId    模型返回的工具调用 ID，用于和 tool result 做精确关联
     */
    record ToolCall(
            String toolId,
            @org.springframework.lang.Nullable String toolName,
            String inputJson,
            long latencyMs,
            @org.springframework.lang.Nullable String callId
    ) implements ReactStep {
        public ToolCall(String toolId,
                        @org.springframework.lang.Nullable String toolName,
                        String inputJson,
                        long latencyMs) {
            this(toolId, toolName, inputJson, latencyMs, null);
        }

        public ToolCall {
            if (callId != null && callId.isBlank()) {
                callId = null;
            }
        }
    }

    /**
     * 工具调用结果观察。
     *
     * @param toolId     工具标识（技术 ID）
     * @param toolName   工具显示名称（用户可读），为 null 时前端回退到 toolId
     * @param success    是否成功
     * @param output     工具输出内容
     * @param tokensUsed 本次调用消耗的 Token 数
     * @param callId     对应的工具调用 ID，用于保持 transcript 回放一致性
     */
    record Observation(
            String toolId,
            @org.springframework.lang.Nullable String toolName,
            boolean success,
            String output,
            int tokensUsed,
            @org.springframework.lang.Nullable String callId
    ) implements ReactStep {
        public Observation(String toolId,
                           @org.springframework.lang.Nullable String toolName,
                           boolean success,
                           String output,
                           int tokensUsed) {
            this(toolId, toolName, success, output, tokensUsed, null);
        }

        public Observation {
            if (callId != null && callId.isBlank()) {
                callId = null;
            }
        }
    }

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

    /** 回顾触发原因。 */
    enum ReflectTrigger {
        TOOL_FAILURE,     // 工具执行失败
        PERIODIC,         // 周期性检查点
        STALL_DETECTED    // 停滞检测（同一工具连续调用）
    }

    /** 执行回顾 — 系统在特定触发点注入，促使 LLM 评估执行进展并调整策略。 */
    record Reflect(String content, ReflectTrigger trigger) implements ReactStep {}
}
