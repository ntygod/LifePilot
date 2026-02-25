package com.lifepilot.interaction.middleware.auth;

import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;

/**
 * 认证策略 sealed interface，按通道类型分发认证逻辑。
 *
 * <p>每个通道实现自己的认证策略，当前仅支持 CLI 通道（{@link CliAuthStrategy}），
 * Web 通道策略将在后续 spec 中添加。
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface AuthStrategy permits CliAuthStrategy {

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
