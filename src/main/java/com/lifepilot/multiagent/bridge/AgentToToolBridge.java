package com.lifepilot.multiagent.bridge;

import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.execution.HandoffToolFactory;
import com.lifepilot.multiagent.model.AgentRegistryEvent;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * Agent→Tool 事件驱动桥接 — 监听 AgentRegistryEvent，自动注册/注销 HandoffTool。
 *
 * <p>当 Agent 注册时，通过 {@link HandoffToolFactory} 创建对应的 BuiltinTool
 * 并注册到 {@link DynamicToolRegistry}；当 Agent 注销时，同步注销对应的 HandoffTool。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class AgentToToolBridge {

    private static final Logger log = LoggerFactory.getLogger(AgentToToolBridge.class);

    private final DynamicToolRegistry toolRegistry;
    private final HandoffToolFactory handoffToolFactory;
    private final MultiAgentProperties config;

    public AgentToToolBridge(DynamicToolRegistry toolRegistry,
                             HandoffToolFactory handoffToolFactory,
                             MultiAgentProperties config) {
        this.toolRegistry = toolRegistry;
        this.handoffToolFactory = handoffToolFactory;
        this.config = config;
    }

    /**
     * 监听 Agent 注册事件，创建 HandoffTool 并注册到 DynamicToolRegistry。
     *
     * <p>仅在 {@code registerHandoffTools=true} 时执行注册。
     * 注册失败仅记录 WARN 日志，不影响 AgentRegistry 状态。</p>
     *
     * @param event Agent 注册事件
     */
    @EventListener
    public void onAgentRegistered(AgentRegistryEvent.AgentRegistered event) {
        if (!config.isRegisterHandoffTools()) {
            log.debug("HandoffTool 自动注册已禁用，跳过: agentId={}", event.definition().id());
            return;
        }

        try {
            BuiltinTool handoffTool = handoffToolFactory.createHandoffTool(event.definition());
            toolRegistry.registerBuiltinTool(handoffTool);
            log.info("HandoffTool 桥接注册成功: agentId={}, toolId={}",
                    event.definition().id(), handoffTool.id());
        } catch (Exception e) {
            log.warn("HandoffTool 桥接注册失败: agentId={}, error={}",
                    event.definition().id(), e.getMessage(), e);
        }
    }

    /**
     * 监听 Agent 注销事件，注销对应的 HandoffTool。
     *
     * <p>注销失败仅记录 WARN 日志，不影响 AgentRegistry 状态。</p>
     *
     * @param event Agent 注销事件
     */
    @EventListener
    public void onAgentUnregistered(AgentRegistryEvent.AgentUnregistered event) {
        String toolId = HandoffToolFactory.TOOL_ID_PREFIX + event.agentId();
        try {
            boolean removed = toolRegistry.unregisterBuiltinTool(toolId);
            if (removed) {
                log.info("HandoffTool 桥接注销成功: agentId={}, toolId={}", event.agentId(), toolId);
            } else {
                log.debug("HandoffTool 桥接注销跳过（工具不存在）: toolId={}", toolId);
            }
        } catch (Exception e) {
            log.warn("HandoffTool 桥接注销失败: agentId={}, toolId={}, error={}",
                    event.agentId(), toolId, e.getMessage(), e);
        }
    }
}
