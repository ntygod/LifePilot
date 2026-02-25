package com.lifepilot.interaction.middleware.auth;

import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI 通道认证策略，本地 CLI 用户完全信任，无需额外验证。
 *
 * @author zsg
 * @since 2026-02-25
 */
public final class CliAuthStrategy implements AuthStrategy {

    private static final Logger log = LoggerFactory.getLogger(CliAuthStrategy.class);

    @Override
    public AuthResult authenticate(GatewayMessage message) {
        log.debug("CLI 通道认证通过: userId={}", message.userId());
        return AuthResult.success(message.userId(), TrustLevel.TRUSTED);
    }

    @Override
    public ChannelType supportedChannel() {
        return ChannelType.CLI;
    }
}
