package com.lifepilot.multiagent.execution;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HandoffTool 工厂 — 为每个 AgentDefinition 创建对应的 BuiltinTool 实例。
 *
 * <p>工具 ID 格式：{@code handoff_to_{agentId}}。executor lambda 内部
 * 委托 {@link AgentExecutor#execute} 执行子 Agent 任务。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class HandoffToolFactory {

    private static final Logger log = LoggerFactory.getLogger(HandoffToolFactory.class);

    /** HandoffTool ID 前缀。 */
    public static final String TOOL_ID_PREFIX = "handoff_to_";

    private final AgentExecutor agentExecutor;

    public HandoffToolFactory(AgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }

    /**
     * 为指定 AgentDefinition 创建 BuiltinTool 实例。
     *
     * @param definition Agent 蓝图
     * @return 包装后的 BuiltinTool
     */
    public BuiltinTool createHandoffTool(AgentDefinition definition) {
        String toolId = TOOL_ID_PREFIX + definition.id();
        String description = "委托给 %s: %s".formatted(definition.name(), definition.description());

        // 输入 Schema：task（必填）+ context（选填）
        JsonSchema inputSchema = JsonSchema.of(Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of("type", "string", "description", "委托任务描述"),
                        "context", Map.of("type", "string", "description", "附加上下文信息")
                ),
                "required", List.of("task")
        ));

        return BuiltinTool.builder()
                .id(toolId)
                .name(definition.name())
                .description(description)
                .inputSchema(inputSchema)
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(List.of("handoff", "multi-agent"))
                .executor(input -> {
                    try {
                        String task = input.getParam("task", String.class);
                        String context = input.getOptionalParam("context", String.class)
                                .orElse(null);

                        // 从 ToolInput 读取调用方上下文（由 ReactAgentLoop 注入）
                        int callerDepth = input.getOptionalParam("_callerDepth", Integer.class).orElse(0);
                        String callerTraceId = input.getOptionalParam("_callerTraceId", String.class).orElse(null);
                        String callerSessionId = input.getOptionalParam("_callerSessionId", String.class)
                                .orElse("handoff-" + UUID.randomUUID().toString().substring(0, 8));

                        // 组装任务消息
                        String message = context != null
                                ? "任务: %s\n上下文: %s".formatted(task, context)
                                : task;

                        // 直接构造 AgentRequest（不再构造旧 AgentState）
                        var subRequest = new AgentRequest(
                                message,
                                callerSessionId,
                                "internal",
                                definition.systemPrompt(),
                                definition.budget().toAgentBudget(),
                                callerTraceId,
                                callerDepth,
                                definition.preferredProvider(),
                                null, // allowedToolIds 由 AgentExecutor 计算
                                null  // 子 Agent 委托不携带多模态媒体
                        );

                        var result = agentExecutor.execute(definition, subRequest);

                        if (result.success()) {
                            return ToolResult.success(Map.of(
                                    "output", result.output(),
                                    "agentId", definition.id(),
                                    "tokensUsed", result.tokensUsed()
                            ));
                        } else {
                            return ToolResult.error("Agent 委托失败: " + result.output());
                        }
                    } catch (Exception e) {
                        log.error("HandoffTool 执行异常: toolId={}, error={}", toolId, e.getMessage(), e);
                        return ToolResult.error("HandoffTool 执行异常: " + e.getMessage());
                    }
                })
                .build();
    }
}
