package com.lifepilot.a2a.server;

import com.lifepilot.a2a.model.A2aAgentCard;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Card REST 端点。
 *
 * <p>提供标准发现路径 {@code /.well-known/agent.json} 和
 * 备用路径 {@code /api/a2a/agent-card}，返回 ZhiWei 的 A2A Agent Card。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
public class AgentCardController {

    private final AgentCardGenerator agentCardGenerator;

    public AgentCardController(AgentCardGenerator agentCardGenerator) {
        this.agentCardGenerator = agentCardGenerator;
    }

    /**
     * 标准 A2A 发现路径（无需认证）。
     *
     * @return Agent Card
     */
    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public A2aAgentCard getAgentCard() {
        return agentCardGenerator.generateCard();
    }

    /**
     * 备用 Agent Card 路径。
     *
     * @return Agent Card
     */
    @GetMapping(value = "/api/a2a/agent-card", produces = MediaType.APPLICATION_JSON_VALUE)
    public A2aAgentCard getAgentCardAlternate() {
        return agentCardGenerator.generateCard();
    }
}
