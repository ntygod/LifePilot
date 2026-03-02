package com.lifepilot.interaction.middleware.auth;

import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Web 通道认证策略，处理来自 Web UI 的 HTTP 请求认证。
 *
 * <p>Web 通道是内部 HTTP 请求，认证策略相对简单：
 * <ul>
 *   <li>如果存在 sessionToken，使用 {@link TrustLevel#VERIFIED} 信任等级</li>
 *   <li>如果没有 sessionToken，使用 {@link TrustLevel#ANONYMOUS} 信任等级（但认证仍然通过）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-01
 */
@Component
public final class WebAuthStrategy implements AuthStrategy {

    private static final Logger log = LoggerFactory.getLogger(WebAuthStrategy.class);

    public WebAuthStrategy() {
        // Web 通道认证策略无需额外依赖
    }

    @Override
    public AuthResult authenticate(GatewayMessage message) {
        if (!(message.channelMetadata() instanceof ChannelMetadata.WebMetadata meta)) {
            return AuthResult.failure("通道元数据类型不匹配: 期望 WebMetadata");
        }

        // Web 通道是内部 HTTP 请求，直接通过认证
        // 根据是否有 sessionToken 决定信任等级
        String sessionToken = meta.sessionToken();
        TrustLevel trustLevel = (sessionToken != null && !sessionToken.isBlank())
                ? TrustLevel.VERIFIED
                : TrustLevel.ANONYMOUS;

        log.debug("Web 认证通过: userId={}, trustLevel={}, hasSessionToken={}",
                message.userId(), trustLevel, sessionToken != null);

        return AuthResult.success(message.userId(), trustLevel);
    }

    @Override
    public ChannelType supportedChannel() {
        return ChannelType.WEB;
    }
}
