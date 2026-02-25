package com.lifepilot.interaction.middleware.auth;

import java.util.Map;

import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 认证鉴权中间件，根据通道类型选择认证策略执行认证。
 *
 * <p>从 {@code Map<ChannelType, AuthStrategy>} 中查找匹配的策略：
 * <ul>
 *   <li>无匹配策略 → 返回 400</li>
 *   <li>认证失败 → 返回 401</li>
 *   <li>认证成功 → 将 {@link AuthResult} 和 {@link TrustLevel} 放入
 *       {@link MiddlewareContext}，调用 {@code chain.next()}</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class AuthMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AuthMiddleware.class);

    private final Map<ChannelType, AuthStrategy> strategies;
    private final GatewayProperties properties;

    /**
     * 构造认证中间件。
     *
     * @param strategies 通道类型到认证策略的映射
     * @param properties 网关配置属性
     */
    public AuthMiddleware(Map<ChannelType, AuthStrategy> strategies, GatewayProperties properties) {
        this.strategies = Map.copyOf(strategies);
        this.properties = properties;
    }

    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        // 1. 查找匹配的认证策略
        var strategy = strategies.get(message.channelType());
        if (strategy == null) {
            log.warn("未找到通道类型的认证策略: channelType={}", message.channelType());
            return GatewayResponse.error(message.channelType(), "不支持的通道类型", 400);
        }

        // 2. 执行认证
        var result = strategy.authenticate(message);
        if (!result.authenticated()) {
            log.warn("认证失败: channelType={}, userId={}, reason={}",
                    message.channelType(), message.userId(), result.failureReason());
            return GatewayResponse.error(message.channelType(),
                    "认证失败: " + result.failureReason(), 401);
        }

        // 3. 认证成功，将结果放入上下文并继续管道
        log.debug("认证成功: channelType={}, userId={}, trustLevel={}",
                message.channelType(), result.userId(), result.trustLevel());
        chain.context().set(MiddlewareContext.KEY_AUTH_RESULT, result);
        chain.context().set(MiddlewareContext.KEY_TRUST_LEVEL, result.trustLevel());
        return chain.next(message);
    }

    @Override
    public int order() {
        return properties.middleware().auth().order();
    }

    @Override
    public String name() {
        return "auth";
    }

    @Override
    public boolean enabled() {
        return properties.middleware().auth().enabled();
    }
}
