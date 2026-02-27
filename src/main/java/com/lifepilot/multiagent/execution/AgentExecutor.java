package com.lifepilot.multiagent.execution;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行器 — 隔离执行子 Agent 任务。
 *
 * <p>执行流程：检查委托深度 → 创建独立 Budget → 构建 AgentRequest
 * → 调用 AgentLoop.run() → 转换为 SubAgentResult。
 * 所有异常均被捕获，返回 success=false 的 SubAgentResult。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final AgentLoop agentLoop;
    private final DynamicToolRegistry toolRegistry;
    private final MultiAgentProperties config;

    public AgentExecutor(AgentLoop agentLoop,
                         DynamicToolRegistry toolRegistry,
                         MultiAgentProperties config) {
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    /**
     * 执行 Agent 委托任务。
     *
     * @param definition  Agent 蓝图
     * @param task        委托任务描述
     * @param context     附加上下文（可选）
     * @param parentState 父 Agent 状态
     * @return SubAgentResult（success=true 或 success=false）
     */
    public Action.SubAgentResult execute(AgentDefinition definition,
                                         String task,
                                         @Nullable String context,
                                         AgentState parentState) {
        String agentId = definition.id();
        int newDepth = parentState.depth() + 1;

        // 1. 检查委托深度
        if (newDepth > config.getMaxDelegationDepth()) {
            log.warn("Agent 委托深度超限: agentId={}, depth={}, maxDepth={}",
                    agentId, newDepth, config.getMaxDelegationDepth());
            return new Action.SubAgentResult(
                    "", agentId, false,
                    "委托深度超限: depth=%d, maxDepth=%d".formatted(newDepth, config.getMaxDelegationDepth()),
                    0);
        }

        try {
            // 2. 创建独立 Budget
            var subBudget = definition.budget().toAgentBudget();

            // 3. 构建 allowedToolIds（canDelegate=false 时排除 handoff_to_* 工具）
            List<String> allowedToolIds = buildAllowedToolIds(definition);

            // 4. 组装任务消息
            String message = context != null
                    ? "任务: %s\n上下文: %s".formatted(task, context)
                    : task;

            // 5. 构建 AgentRequest
            var subRequest = new AgentRequest(
                    message,
                    parentState.sessionId(),
                    parentState.channel(),
                    definition.systemPrompt(),
                    subBudget,
                    parentState.traceId(),
                    newDepth,
                    definition.preferredProvider(),
                    allowedToolIds
            );

            // 6. 执行 AgentLoop
            log.info("Agent 委托执行开始: agentId={}, depth={}, task={}",
                    agentId, newDepth, task.length() > 100 ? task.substring(0, 100) + "..." : task);

            AgentResponse response = agentLoop.run(subRequest);

            log.info("Agent 委托执行完成: agentId={}, tokensUsed={}, steps={}",
                    agentId, response.tokensUsed(), response.stepCount());

            // 7. 转换为 SubAgentResult
            return new Action.SubAgentResult(
                    response.traceId(),
                    agentId,
                    response.terminationReason() == null,
                    response.content(),
                    response.tokensUsed());

        } catch (Exception e) {
            log.warn("Agent 委托执行异常: agentId={}, error={}", agentId, e.getMessage(), e);
            return new Action.SubAgentResult(
                    "", agentId, false,
                    "Agent 执行异常: " + e.getMessage(),
                    0);
        }
    }

    /**
     * 构建子 Agent 的工具白名单。
     *
     * <p>如果 canDelegate=false，从 allowedTools 中排除 handoff_to_* 工具。
     * 对于 allowedTools 中不存在于 DynamicToolRegistry 的工具 ID，记录 WARN 日志并跳过。</p>
     */
    private List<String> buildAllowedToolIds(AgentDefinition definition) {
        var result = new ArrayList<String>();
        for (String toolId : definition.allowedTools()) {
            // 跳过不存在的工具
            if (toolRegistry.resolve(toolId).isEmpty()) {
                log.warn("Agent 工具白名单中的工具不存在: agentId={}, toolId={}",
                        definition.id(), toolId);
                continue;
            }
            // canDelegate=false 时排除 handoff_to_* 工具
            if (!definition.canDelegate() && toolId.startsWith(HandoffToolFactory.TOOL_ID_PREFIX)) {
                continue;
            }
            result.add(toolId);
        }
        return List.copyOf(result);
    }
}
