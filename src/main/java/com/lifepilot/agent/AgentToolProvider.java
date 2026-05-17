package com.lifepilot.agent;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolSchedulingMode;
import jakarta.annotation.Nullable;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具桥接层接口。
 *
 * <p>AgentLoop 通过此接口获取工具回调，将 ToolContract 转换为 Spring AI ToolCallback。
 * 默认实现为 {@code ToolBridgeAgentToolProvider}，通过 ToolExecutionPipeline 执行工具调用。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public interface AgentToolProvider {

    /**
     * 获取工具回调列表。
     *
     * @param state 当前 ReAct Agent 状态
     * @param streamId SSE 流标识（用于精确推送授权审批请求，CLI 场景为 null）
     * @return 工具回调列表（Spring AI ToolCallback）
     */
    List<ToolCallback> getToolCallbacks(ReactAgentState state, @Nullable String streamId);

    /**
     * 获取工具回调列表（携带产物 sink）。
     *
     * <p>{@link #getToolCallbacks(ReactAgentState, String)} 的扩展重载，让 Agent 主循环
     * 把 {@code AgentLoopContext::addArtifactRefs} 之类的 Consumer 透传给工具桥接层，
     * 让工具执行后产生的 {@code ToolResult.artifacts()} 经 sink 流回主循环，
     * 升级为会话产物并通过渠道分发。</p>
     *
     * <p>默认实现忽略 sink 调用基础重载，保持向后兼容。</p>
     *
     * @param state        当前 ReAct Agent 状态
     * @param streamId     SSE 流标识
     * @param artifactSink 产物 sink，工具执行后由桥接层 push artifacts；为 null 时关闭旁路
     * @return 工具回调列表
     */
    default List<ToolCallback> getToolCallbacks(
            ReactAgentState state,
            @Nullable String streamId,
            @Nullable java.util.function.Consumer<java.util.List<com.lifepilot.tool.model.ToolArtifact>> artifactSink) {
        return getToolCallbacks(state, streamId);
    }

    /**
     * 根据工具 ID 解析用户可读的显示名称。
     *
     * <p>用于前端展示场景（推理时间线、工具确认卡片等），
     * 将技术 ID（如 {@code todo.create}）转换为中文名称（如 "创建待办"）。</p>
     *
     * @param toolId 工具技术标识
     * @return 工具显示名称，未找到时返回 null（前端回退到 toolId）
     */
    @Nullable
    default String resolveToolDisplayName(String toolId) {
        return null;
    }

    /**
     * 根据工具 ID 解析风险等级。
     *
     * <p>用于 Trace 记录，避免工具步骤被一律记成低风险。</p>
     *
     * @param toolId 工具技术标识
     * @return 工具风险等级，未找到时回退为 LOW
     */
    default RiskLevel resolveToolRiskLevel(String toolId) {
        return RiskLevel.LOW;
    }

    /**
     * 根据工具 ID 和输入 JSON 解析调度提示。
     *
     * <p>授权与调度完全解耦：这里仅决定工具能否并行、是否需要按资源串行，
     * 不参与风险评估与审批决策。</p>
     *
     * @param toolId 工具技术标识
     * @param inputJson 工具输入 JSON
     * @return 调度提示，默认串行
     */
    default ToolSchedulingHint resolveSchedulingHint(String toolId, String inputJson) {
        return ToolSchedulingHint.sequential();
    }

    /**
     * 将模型返回的工具名解析为内部工具 ID。
     *
     * <p>部分 Provider 对工具名格式有限制，桥接层可能会对外暴露合法别名；
     * 执行阶段再通过该方法恢复为真实工具 ID。</p>
     *
     * @param toolId 模型返回的工具名或原始工具 ID
     * @return 内部真实工具 ID，默认原样返回
     */
    default String resolveCanonicalToolId(String toolId) {
        return toolId;
    }

    /**
     * 工具调度提示。
     *
     * @param mode 调度模式
     * @param resourceKeys 资源集合，RESOURCE_SERIALIZED 模式下用于冲突检测
     */
    record ToolSchedulingHint(
            ToolSchedulingMode mode,
            List<String> resourceKeys
    ) {
        public ToolSchedulingHint {
            mode = mode != null ? mode : ToolSchedulingMode.SEQUENTIAL;
            resourceKeys = resourceKeys == null ? List.of() : resourceKeys.stream()
                    .filter(key -> key != null && !key.isBlank())
                    .distinct()
                    .collect(Collectors.toUnmodifiableList());
        }

        public static ToolSchedulingHint sequential() {
            return new ToolSchedulingHint(ToolSchedulingMode.SEQUENTIAL, List.of());
        }

        public static ToolSchedulingHint parallelSafe() {
            return new ToolSchedulingHint(ToolSchedulingMode.PARALLEL_SAFE, List.of());
        }

        public static ToolSchedulingHint resourceSerialized(List<String> resourceKeys) {
            return new ToolSchedulingHint(ToolSchedulingMode.RESOURCE_SERIALIZED, resourceKeys);
        }
    }
}
