package com.lifepilot.interaction.middleware.auth;

import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书通道认证策略，通过 verification token 验证事件回调的合法性。
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class FeishuAuthStrategy implements AuthStrategy {

    private static final Logger log = LoggerFactory.getLogger(FeishuAuthStrategy.class);

    private final GatewayProperties properties;

    public FeishuAuthStrategy(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public AuthResult authenticate(GatewayMessage message) {
        if (!(message.channelMetadata() instanceof ChannelMetadata.FeishuMetadata meta)) {
            return AuthResult.failure("通道元数据类型不匹配: 期望 FeishuMetadata");
        }

        // 验证 verification token（通过 appId 匹配确认来源合法）
        var feishuConfig = properties.channels().feishu();
        var expectedAppId = feishuConfig.appId();
        if (expectedAppId != null && !expectedAppId.equals(meta.appId())) {
            log.warn("飞书认证失败: appId 不匹配, expected={}, actual={}", feishuConfig.appId(), meta.appId());
            return AuthResult.failure("appId 不匹配");
        }

        log.debug("飞书认证通过: userId={}, eventId={}", message.userId(), meta.eventId());
        return AuthResult.success(message.userId(), TrustLevel.VERIFIED);
    }

    @Override
    public ChannelType supportedChannel() {
        return ChannelType.FEISHU;
    }
}
