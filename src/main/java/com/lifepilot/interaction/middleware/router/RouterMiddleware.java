package com.lifepilot.interaction.middleware.router;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.model.TokenUsage;

/**
 * 意图路由中间件，根据消息类型分发到快速路径或 Agent 路径。
 *
 * <p>快速路径：命令消息（{@link MessageContent.CommandMessage} 或以 {@code /} 开头的文本消息）
 * 匹配已知命令后直接返回响应，不经过 Agent，{@link TokenUsage} 为零。
 *
 * <p>慢速路径：自然语言文本消息，创建 {@link RouteDecision.AgentRoute} 放入
 * {@link MiddlewareContext}，交由后续 ExecutionMiddleware 处理。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class RouterMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(RouterMiddleware.class);

    private final GatewayProperties properties;

    public RouterMiddleware(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        if (isCommandMessage(message)) {
            log.debug("检测到命令消息，进入快速路径: messageId={}", message.messageId());
            return handleFastPath(message);
        }

        // 慢速路径：自然语言消息
        String content = message.contentAsText();
        var decision = new RouteDecision.AgentRoute(content);
        chain.context().set(MiddlewareContext.KEY_ROUTE_DECISION, decision);
        log.debug("自然语言消息，路由到 Agent: messageId={}", message.messageId());
        return chain.next(message);
    }

    @Override
    public int order() {
        return properties.middleware().router().order();
    }

    @Override
    public String name() {
        return "router";
    }

    @Override
    public boolean enabled() {
        return properties.middleware().router().enabled();
    }

    /**
     * 判断消息是否为命令消息。
     *
     * <p>满足以下任一条件即为命令消息：
     * <ul>
     *   <li>内容类型为 {@link MessageContent.CommandMessage}</li>
     *   <li>内容类型为 {@link MessageContent.TextMessage} 且文本以 {@code /} 开头</li>
     * </ul>
     */
    private boolean isCommandMessage(GatewayMessage message) {
        return message.content() instanceof MessageContent.CommandMessage
                || (message.content() instanceof MessageContent.TextMessage t
                    && t.text().startsWith("/"));
    }

    /**
     * 处理快速路径命令。
     *
     * <p>解析命令名后检查是否在 {@code fastPathCommands} 列表中：
     * <ul>
     *   <li>已知命令 → 返回成功响应，{@link TokenUsage#ZERO}</li>
     *   <li>未知命令 → 返回 400 错误，列出可用命令</li>
     * </ul>
     */
    private GatewayResponse handleFastPath(GatewayMessage message) {
        String command = extractCommand(message);
        List<String> fastPathCommands = properties.router().fastPathCommands();

        if (fastPathCommands.contains(command)) {
            log.info("快速路径命令匹配: command={}, messageId={}", command, message.messageId());
            return GatewayResponse.success(
                    message.channelType(),
                    new ResponseContent.TextContent("快速路径命令: /" + command)
            ).toBuilder().tokenUsage(TokenUsage.ZERO).build();
        }

        // 未知命令，返回可用命令列表
        String available = String.join(", ", fastPathCommands.stream().map(c -> "/" + c).toList());
        log.warn("未知命令: /{}, messageId={}", command, message.messageId());
        return GatewayResponse.error(
                message.channelType(),
                "未知命令: /" + command + "。可用命令: " + available,
                400
        );
    }

    /**
     * 从消息中提取命令名。
     */
    private String extractCommand(GatewayMessage message) {
        return switch (message.content()) {
            case MessageContent.CommandMessage cmd -> cmd.command();
            case MessageContent.TextMessage t -> {
                // 文本以 / 开头，解析命令名
                String stripped = t.text().substring(1).strip();
                String[] parts = stripped.split("\\s+");
                yield parts.length > 0 ? parts[0] : "";
            }
            default -> "";
        };
    }

}
