package com.lifepilot.meta.convenience;

import java.util.List;

/**
 * 能力摘要 — 聚合各注册中心信息的统一视图。
 *
 * @param skills     Skill 能力列表
 * @param agents     Agent 能力列表
 * @param tools      工具能力列表
 * @param workflows  工作流能力列表
 * @param mcpServers MCP Server 能力列表
 * @author zsg
 * @since 2026-03-08
 */
public record CapabilitySummary(
        List<CapabilityInfo> skills,
        List<CapabilityInfo> agents,
        List<CapabilityInfo> tools,
        List<CapabilityInfo> workflows,
        List<CapabilityInfo> mcpServers
) {

    /** 紧凑构造器 — 防御性拷贝。 */
    public CapabilitySummary {
        skills = List.copyOf(skills);
        agents = List.copyOf(agents);
        tools = List.copyOf(tools);
        workflows = List.copyOf(workflows);
        mcpServers = List.copyOf(mcpServers);
    }

    /** 返回所有能力的总数。 */
    public int totalCount() {
        return skills.size() + agents.size() + tools.size() + workflows.size() + mcpServers.size();
    }
}
