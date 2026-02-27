package com.lifepilot.multiagent.model;

/**
 * Agent 注册中心事件密封接口。
 *
 * <p>通过 Spring ApplicationEventPublisher 发布，AgentToToolBridge 监听。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public sealed interface AgentRegistryEvent permits
        AgentRegistryEvent.AgentRegistered,
        AgentRegistryEvent.AgentUnregistered {

    /** Agent 注册事件。 */
    record AgentRegistered(AgentDefinition definition) implements AgentRegistryEvent {}

    /** Agent 注销事件。 */
    record AgentUnregistered(String agentId) implements AgentRegistryEvent {}
}
