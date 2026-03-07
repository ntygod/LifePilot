package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.DependencyGraphResponse;
import com.lifepilot.interaction.web.model.DependencyGraphResponse.EdgeInfo;
import com.lifepilot.interaction.web.model.DependencyGraphResponse.NodeInfo;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖关系图 REST Controller。
 *
 * <p>遍历 AgentRegistry、SkillRegistry 和 DynamicToolRegistry，
 * 构建 Agent → Tool、Skill → Tool 的依赖关系图并返回。</p>
 *
 * @author zsg
 * @since 2026-03-07
 */
@RestController
@RequestMapping("/api/dependencies")
public class DependencyController {

    private static final Logger log = LoggerFactory.getLogger(DependencyController.class);

    private final AgentRegistry agentRegistry;
    private final SkillRegistry skillRegistry;
    private final DynamicToolRegistry toolRegistry;

    public DependencyController(AgentRegistry agentRegistry,
                                 SkillRegistry skillRegistry,
                                 DynamicToolRegistry toolRegistry) {
        this.agentRegistry = agentRegistry;
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 获取依赖关系图。
     *
     * <p>遍历三个注册中心，构建节点和边：
     * <ul>
     *   <li>Agent 节点 + allowedTools 边（relation: allowed-tool）</li>
     *   <li>Skill 节点 + allowedTools 边（relation: uses-tool）</li>
     *   <li>Tool 节点（来自 DynamicToolRegistry）</li>
     * </ul>
     * 节点按 ID 去重。</p>
     *
     * @return 依赖关系图响应
     */
    @GetMapping("/graph")
    public ResponseEntity<DependencyGraphResponse> getGraph() {
        // 使用 LinkedHashMap 按 ID 去重，保持插入顺序
        Map<String, NodeInfo> nodeMap = new LinkedHashMap<>();
        List<EdgeInfo> edges = new ArrayList<>();

        // 1. 遍历 Agent → 节点 + allowedTools 边
        for (var agent : agentRegistry.listAll()) {
            nodeMap.put(agent.id(), new NodeInfo(agent.id(), agent.name(), "AGENT", true));
            for (String toolId : agent.allowedTools()) {
                edges.add(new EdgeInfo(agent.id(), toolId, "allowed-tool"));
            }
        }

        // 2. 遍历 Skill → 节点 + suggestedTools 边
        for (var skill : skillRegistry.listAll()) {
            nodeMap.put(skill.id(), new NodeInfo(skill.id(), skill.name(), "SKILL", true));
            for (String toolId : skill.suggestedTools()) {
                edges.add(new EdgeInfo(skill.id(), toolId, "uses-tool"));
            }
        }

        // 3. 遍历 Tool → 节点
        for (var tool : toolRegistry.getAllTools()) {
            nodeMap.put(tool.id(), new NodeInfo(tool.id(), tool.name(), "TOOL", true));
        }

        var response = new DependencyGraphResponse(List.copyOf(nodeMap.values()), List.copyOf(edges));
        log.debug("依赖关系图构建完成: nodes={}, edges={}", response.nodes().size(), response.edges().size());
        return ResponseEntity.ok(response);
    }
}
