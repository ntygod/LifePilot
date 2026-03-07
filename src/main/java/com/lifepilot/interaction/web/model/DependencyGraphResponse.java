package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 依赖关系图响应。
 *
 * @param nodes 节点列表（Agent / Skill / Tool）
 * @param edges 边列表（引用关系）
 * @author zsg
 * @since 2026-03-07
 */
public record DependencyGraphResponse(
        List<NodeInfo> nodes,
        List<EdgeInfo> edges
) {

    /**
     * 依赖图节点信息。
     *
     * @param id      节点唯一标识
     * @param name    节点显示名称
     * @param type    节点类型（AGENT / SKILL / TOOL）
     * @param enabled 是否启用
     */
    public record NodeInfo(String id, String name, String type, boolean enabled) {}

    /**
     * 依赖图边信息。
     *
     * @param source   源节点 ID
     * @param target   目标节点 ID
     * @param relation 关系类型（如 uses-skill、uses-tool、allowed-tool）
     */
    public record EdgeInfo(String source, String target, String relation) {}
}
