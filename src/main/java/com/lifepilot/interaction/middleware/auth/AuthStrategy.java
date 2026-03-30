package com.lifepilot.interaction.middleware.auth;

import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;

/**
 * 认证策略 sealed interface，按通道类型分发认证逻辑。
 *
 * <p>当前主服务仅保留 Web 本地渠道的显式认证策略。
 * 外部渠道由 connector runtime 完成实例级鉴权后，再通过 trace header 把认证结果透传进来。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface AuthStrategy permits WebAuthStrategy {

    /**
     * 对网关消息执行认证。
     *
     * @param message 待认证的网关消息
     * @return 认证结果
     */
    AuthResult authenticate(GatewayMessage message);

    /**
     * 返回此策略支持的通道类型。
     *
     * @return 通道类型
     */
    ChannelType supportedChannel();
}
