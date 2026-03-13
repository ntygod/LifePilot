package com.lifepilot.workflow.model;

import java.util.List;

/**
 * DAG 依赖图数据。
 *
 * @param nodes  节点列表
 * @param edges  边列表
 * @param levels 拓扑层级数
 * @author zsg
 * @since 2026-03-13
 */
public record DagData(List<DagNode> nodes, List<DagEdge> edges, int levels) {
    public DagData {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
