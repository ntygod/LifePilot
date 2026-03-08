package com.lifepilot.a2a.server;

import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.A2aAgentCapabilities;
import com.lifepilot.a2a.model.A2aAgentCard;
import com.lifepilot.a2a.model.A2aAgentSkill;
import com.lifepilot.multiagent.registry.AgentRegistry;

import java.util.List;

/**
 * A2A Agent Card 生成器。
 *
 * <p>从 AgentRegistry 读取已注册 Agent，映射为 A2aAgentSkill，
 * 结合 A2aProperties 中的配置生成完整的 A2aAgentCard。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class AgentCardGenerator {

    private final AgentRegistry agentRegistry;
    private final A2aProperties properties;

    public AgentCardGenerator(AgentRegistry agentRegistry, A2aProperties properties) {
        this.agentRegistry = agentRegistry;
        this.properties = properties;
    }

    /** 生成 ZhiWei 主 Agent 的 Agent Card。 */
    public A2aAgentCard generateCard() {
        var server = properties.getServer();

        // 从 AgentRegistry 映射 skills
        List<A2aAgentSkill> skills = agentRegistry.listAll().stream()
                .map(def -> new A2aAgentSkill(
                        def.id(),
                        def.name(),
                        def.description(),
                        List.of("text"),
                        List.of("text")))
                .toList();

        var capabilities = new A2aAgentCapabilities(server.isStreamingEnabled());

        return new A2aAgentCard(
                server.getAgentName(),
                server.getAgentDescription(),
                "",  // url 由 Controller 层根据请求上下文填充
                server.getAgentVersion(),
                server.getProtocolVersion(),
                skills,
                capabilities,
                List.of("text"),
                List.of("text"),
                null);
    }
}
