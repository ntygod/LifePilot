package com.lifepilot.interaction.middleware.execution;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.model.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 执行中间件，将 GatewayMessage 转换为 AgentRequest 并调用 AgentLoop。
 *
 * <p>负责 GatewayMessage → AgentRequest 的转换、带超时的 AgentLoop 调用、
 * AgentResponse → GatewayResponse 的转换，以及异常处理（超时、执行异常、中断）。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ExecutionMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ExecutionMiddleware.class);

    private final AgentLoop agentLoop;
    private final GatewayProperties properties;

    public ExecutionMiddleware(AgentLoop agentLoop, GatewayProperties properties) {
        this.agentLoop = agentLoop;
        this.properties = properties;
    }

    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        // 1. GatewayMessage → AgentRequest
        var agentRequest = new AgentRequest(
                message.contentAsText(),
                message.sessionId(),
                message.channelType().value()
        );

        // 2. 带超时调用 AgentLoop.run()
        try {
            var future = CompletableFuture.supplyAsync(() -> agentLoop.run(agentRequest));
            int timeout = properties.execution().timeoutSeconds();
            var agentResponse = future.get(timeout, TimeUnit.SECONDS);

            // 3. AgentResponse → GatewayResponse，构建 TokenUsage
            var tokenUsage = new TokenUsage(0, 0, agentResponse.tokensUsed(), "agent");
            chain.context().set(MiddlewareContext.KEY_AGENT_RESPONSE, agentResponse);
            chain.context().set(MiddlewareContext.KEY_TOKEN_USAGE, tokenUsage);

            return GatewayResponse.success(message.channelType(),
                            new ResponseContent.TextContent(agentResponse.content()))
                    .toBuilder().tokenUsage(tokenUsage).build();

        } catch (TimeoutException e) {
            log.warn("Agent 执行超时: messageId={}, timeout={}s", message.messageId(),
                    properties.execution().timeoutSeconds());
            return GatewayResponse.error(message.channelType(), "请求处理超时", 504);
        } catch (ExecutionException e) {
            log.error("Agent 执行异常: messageId={}", message.messageId(), e.getCause());
            return GatewayResponse.error(message.channelType(), "处理请求时发生内部错误", 500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Agent 执行被中断: messageId={}", message.messageId());
            return GatewayResponse.error(message.channelType(), "请求被中断", 500);
        }
    }

    @Override
    public int order() {
        return properties.middleware().execution().order();
    }

    @Override
    public String name() {
        return "execution";
    }

    @Override
    public boolean enabled() {
        return properties.middleware().execution().enabled();
    }
}
