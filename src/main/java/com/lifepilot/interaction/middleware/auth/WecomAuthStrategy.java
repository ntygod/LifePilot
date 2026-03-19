package com.lifepilot.interaction.middleware.auth;

import com.lifepilot.interaction.channel.wecom.WecomSignatureVerifier;
import com.lifepilot.interaction.config.ChannelConfigProvider;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企业微信通道认证策略，通过 SHA1 签名验证 Webhook 回调的合法性。
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class WecomAuthStrategy implements AuthStrategy {

    private static final Logger log = LoggerFactory.getLogger(WecomAuthStrategy.class);

    private final WecomSignatureVerifier signatureVerifier;
    private final GatewayProperties properties;
    private final ChannelConfigProvider configProvider;

    public WecomAuthStrategy(WecomSignatureVerifier signatureVerifier, GatewayProperties properties,
                             ChannelConfigProvider configProvider) {
        this.signatureVerifier = signatureVerifier;
        this.properties = properties;
        this.configProvider = configProvider;
    }

    @Override
    public AuthResult authenticate(GatewayMessage message) {
        if (!(message.channelMetadata() instanceof ChannelMetadata.WecomMetadata meta)) {
            return AuthResult.failure("通道元数据类型不匹配: 期望 WecomMetadata");
        }

        // 时间戳容忍窗口检查
        long tolerance = properties.webhook().timestampToleranceSeconds();
        try {
            long ts = Long.parseLong(meta.timestamp());
            long now = System.currentTimeMillis() / 1000;
            if (Math.abs(now - ts) > tolerance) {
                log.warn("企微签名验证失败: 时间戳过期, timestamp={}, tolerance={}s", ts, tolerance);
                return AuthResult.failure("时间戳过期");
            }
        } catch (NumberFormatException e) {
            return AuthResult.failure("时间戳格式无效: " + meta.timestamp());
        }

        // SHA1 签名验证
        boolean valid = signatureVerifier.verify(
                meta.msgSignature(), meta.timestamp(), meta.nonce(),
                meta.encryptedMsg() != null ? meta.encryptedMsg() : "");
        if (!valid) {
            log.warn("企微签名验证失败: msgSignature={}", maskSensitive(meta.msgSignature()));
            return AuthResult.failure("签名验证失败");
        }

        log.debug("企微认证通过: userId={}", message.userId());
        return AuthResult.success(message.userId(), TrustLevel.VERIFIED);
    }

    @Override
    public ChannelType supportedChannel() {
        return ChannelType.WECOM;
    }

    private static String maskSensitive(String value) {
        if (value == null || value.length() <= 8) return "***";
        return value.substring(0, 8) + "***";
    }
}
