package com.lifepilot.interaction.middleware.auth;

import com.lifepilot.interaction.config.ChannelConfigProvider;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书通道认证策略，通过 appId 匹配确认事件回调的来源合法性。
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class FeishuAuthStrategy implements AuthStrategy {

    private static final Logger log = LoggerFactory.getLogger(FeishuAuthStrategy.class);

    private final ChannelConfigProvider configProvider;

    public FeishuAuthStrategy(ChannelConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    @Override
    public AuthResult authenticate(GatewayMessage message) {
        if (!(message.channelMetadata() instanceof ChannelMetadata.FeishuMetadata meta)) {
            return AuthResult.failure("通道元数据类型不匹配: 期望 FeishuMetadata");
        }

        // 通过 appId 匹配确认来源合法（从 DB + yml 合并配置读取）
        var feishuConfig = configProvider.getFeishuConfig();
        var expectedAppId = feishuConfig.appId();
        if (expectedAppId != null && !expectedAppId.isBlank() && !expectedAppId.equals(meta.appId())) {
            log.warn("飞书认证失败: appId 不匹配, expected={}, actual={}", expectedAppId, meta.appId());
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
