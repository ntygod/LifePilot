package com.lifepilot.multiagent.execution;

import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行器 — 隔离执行子 Agent 任务。
 *
 * <p>执行流程：检查委托深度 → 构建 allowedToolIds → 构造 AgentRequest
 * → 调用 ReactAgentLoop.run() → 转换为 SubAgentResult。
 * 所有异常均被捕获，返回 success=false 的 SubAgentResult。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final ReactAgentLoop reactAgentLoop;
    private final DynamicToolRegistry toolRegistry;
    private final MultiAgentProperties config;

    public AgentExecutor(ReactAgentLoop reactAgentLoop,
                         DynamicToolRegistry toolRegistry,
                         MultiAgentProperties config) {
        this.reactAgentLoop = reactAgentLoop;
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    /**
     * 执行 Agent 委托任务。
     *
     * @param definition Agent 蓝图
     * @param request    已构造的 AgentRequest（含 depth、parentTraceId 等）
     * @return SubAgentResult（success=true 或 success=false）
     */
    public Action.SubAgentResult execute(AgentDefinition definition, AgentRequest request) {
        String agentId = definition.id();
        int newDepth = request.depth() + 1;

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
            // 2. 构建 allowedToolIds（canDelegate=false 时排除 handoff_to_* 工具 + 父作用域交集）
            List<String> allowedToolIds = buildAllowedToolIds(definition, request.allowedToolIds());

            // 3. 构造子 Agent 请求（覆盖 depth + allowedToolIds）
            var subRequest = new AgentRequest(
                    request.message(),
                    request.sessionId(),
                    request.channel(),
                    request.systemPrompt(),
                    request.budget(),
                    request.parentTraceId(),
                    newDepth,
                    request.preferredProvider(),
                    allowedToolIds,
                    null // 子 Agent 委托不携带多模态媒体
            );

            // 4. 执行 ReactAgentLoop
            log.info("Agent 委托执行开始: agentId={}, depth={}, messageLen={}",
                    agentId, newDepth, request.message().length());

            AgentResponse response = reactAgentLoop.run(subRequest);

            log.info("Agent 委托执行完成: agentId={}, tokensUsed={}, steps={}",
                    agentId, response.tokensUsed(), response.stepCount());

            // 5. 转换为 SubAgentResult — 澄清终止视为成功（父 Agent 可展示澄清问题）
            boolean success = response.terminationReason() == null
                    || "需要用户澄清".equals(response.terminationReason());
            return new Action.SubAgentResult(
                    response.traceId(),
                    agentId,
                    success,
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
     * <p>排除规则（按优先级）：
     * <ol>
     *   <li>不存在于 DynamicToolRegistry 的工具 ID — 记录 WARN 日志并跳过</li>
     *   <li>指向自身的 handoff 工具（防止自递归）— 独立于 canDelegate 标志</li>
     *   <li>canDelegate=false 时排除所有 handoff_to_* 工具</li>
     * </ol>
     *
     * <p>交集约束：当父 Agent 的 allowedToolIds 非空时，子 Agent 的有效工具集
     * 取与父 Agent 作用域的交集，防止通过委托实现权限提升。
     * Infrastructure 工具（tags 含 "infrastructure"）始终保留，不受交集约束。</p>
     *
     * @param definition       子 Agent 蓝图
     * @param parentAllowedIds 父 Agent 的工具白名单（可为 null）
     * @return 子 Agent 的有效工具 ID 列表
     */
    private List<String> buildAllowedToolIds(AgentDefinition definition,
                                             List<String> parentAllowedIds) {
        String selfHandoffId = HandoffToolFactory.TOOL_ID_PREFIX + definition.id();
        var result = new ArrayList<String>();
        for (String toolId : definition.allowedTools()) {
            // 跳过不存在的工具
            if (toolRegistry.resolve(toolId).isEmpty()) {
                log.warn("Agent 工具白名单中的工具不存在: agentId={}, toolId={}",
                        definition.id(), toolId);
                continue;
            }
            // 排除指向自身的 handoff 工具（防止自递归，独立于 canDelegate 标志）
            if (toolId.equals(selfHandoffId)) {
                log.debug("排除自递归 handoff 工具: agentId={}, toolId={}", definition.id(), toolId);
                continue;
            }
            // canDelegate=false 时排除所有 handoff_to_* 工具
            if (!definition.canDelegate() && toolId.startsWith(HandoffToolFactory.TOOL_ID_PREFIX)) {
                continue;
            }
            result.add(toolId);
        }

        // 父 Agent 作用域交集约束
        if (parentAllowedIds != null && !parentAllowedIds.isEmpty()) {
            result.retainAll(parentAllowedIds);
        }

        // infrastructure 工具始终保留
        toolRegistry.getToolSnapshot().stream()
                .filter(t -> t.tags().contains("infrastructure"))
                .map(ToolContract::id)
                .forEach(id -> {
                    if (!result.contains(id)) {
                        result.add(id);
                    }
                });

        return List.copyOf(result);
    }
}
