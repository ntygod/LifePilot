package com.lifepilot.a2a.server;

import com.lifepilot.a2a.model.A2aAgentCard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Card REST 端点。
 *
 * <p>提供标准发现路径 {@code /.well-known/agent.json} 和
 * 备用路径 {@code /api/a2a/agent-card}，返回 ZhiWei 的 A2A Agent Card。
 * url 字段从请求上下文动态填充。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
@ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentCardController {

    private final AgentCardGenerator agentCardGenerator;

    public AgentCardController(AgentCardGenerator agentCardGenerator) {
        this.agentCardGenerator = agentCardGenerator;
    }

    /**
     * 标准 A2A 发现路径（无需认证）。
     *
     * @return Agent Card（url 字段已填充）
     */
    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public A2aAgentCard getAgentCard(HttpServletRequest request) {
        return withBaseUrl(agentCardGenerator.generateCard(), request);
    }

    /**
     * 备用 Agent Card 路径。
     *
     * @return Agent Card（url 字段已填充）
     */
    @GetMapping(value = "/api/a2a/agent-card", produces = MediaType.APPLICATION_JSON_VALUE)
    public A2aAgentCard getAgentCardAlternate(HttpServletRequest request) {
        return withBaseUrl(agentCardGenerator.generateCard(), request);
    }

    /** 用请求上下文的 base URL 填充 Agent Card 的 url 字段。 */
    private A2aAgentCard withBaseUrl(A2aAgentCard card, HttpServletRequest request) {
        String baseUrl = request.getScheme() + "://" + request.getServerName()
                + ":" + request.getServerPort();
        return new A2aAgentCard(card.name(), card.description(), baseUrl, card.version(),
                card.protocolVersion(), card.skills(), card.capabilities(),
                card.defaultInputModes(), card.defaultOutputModes(), card.securitySchemes());
    }
}
