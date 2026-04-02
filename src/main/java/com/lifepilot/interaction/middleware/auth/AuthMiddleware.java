package com.lifepilot.interaction.middleware.auth;

import java.util.Map;

import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.InteractionTraceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

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
        AuthResult preAuthenticated = resolvePreAuthenticatedResult(message);
        if (preAuthenticated != null) {
            log.debug("使用 trace header 预认证结果: channelType={}, userId={}, trustLevel={}",
                    message.channelType(), preAuthenticated.userId(), preAuthenticated.trustLevel());
            chain.context().set(MiddlewareContext.KEY_AUTH_RESULT, preAuthenticated);
            chain.context().set(MiddlewareContext.KEY_TRUST_LEVEL, preAuthenticated.trustLevel());
            return chain.next(message);
        }

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

    /**
     * 仅允许内部通道（CHANNEL 类型）使用 trace header 预认证。
     *
     * <p>Web 等外部通道不信任 trace header 中的认证信息，防止请求伪造绕过认证。
     * 仅 ChannelRuntimeIngressService 等内部服务设置这些 header。
     */
    @Nullable
    private AuthResult resolvePreAuthenticatedResult(GatewayMessage message) {
        // 安全防护：仅内部通道类型允许预认证，Web/CLI 等外部通道不信任 trace header
        if (!isInternalChannel(message.channelType())) {
            return null;
        }

        String trustLevelValue = message.traceHeaders().get(InteractionTraceHeaders.AUTH_TRUST_LEVEL);
        if (trustLevelValue == null || trustLevelValue.isBlank()) {
            return null;
        }

        TrustLevel trustLevel;
        try {
            trustLevel = TrustLevel.valueOf(trustLevelValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("忽略无效的预认证信任等级: channelType={}, trustLevel={}",
                    message.channelType(), trustLevelValue);
            return null;
        }

        // 预认证不允许声明 TRUSTED 等级 — TRUSTED 仅由 CLI 本地认证策略授予
        if (trustLevel == TrustLevel.TRUSTED) {
            log.warn("拒绝 trace header 声明 TRUSTED 等级: channelType={}, 降级为 VERIFIED",
                    message.channelType());
            trustLevel = TrustLevel.VERIFIED;
        }

        String userId = message.traceHeaders().getOrDefault(
                InteractionTraceHeaders.AUTH_USER_ID, message.userId());
        if (userId == null || userId.isBlank()) {
            log.warn("忽略缺少 userId 的预认证结果: channelType={}", message.channelType());
            return null;
        }
        return AuthResult.success(userId, trustLevel);
    }

    /**
     * 判断是否为内部通道类型（企业消息平台等服务端签名验证过的通道）。
     */
    private boolean isInternalChannel(ChannelType channelType) {
        return channelType == ChannelType.FEISHU
                || channelType == ChannelType.DINGTALK
                || channelType == ChannelType.WECOM;
    }
}
