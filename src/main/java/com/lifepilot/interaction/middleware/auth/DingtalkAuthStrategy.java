package com.lifepilot.interaction.middleware.auth;

import com.lifepilot.interaction.channel.dingtalk.DingtalkSignatureVerifier;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉通道认证策略，通过 HmacSHA256 签名验证 Webhook 回调的合法性。
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class DingtalkAuthStrategy implements AuthStrategy {

    private static final Logger log = LoggerFactory.getLogger(DingtalkAuthStrategy.class);

    private final DingtalkSignatureVerifier signatureVerifier;
    private final GatewayProperties properties;

    public DingtalkAuthStrategy(DingtalkSignatureVerifier signatureVerifier, GatewayProperties properties) {
        this.signatureVerifier = signatureVerifier;
        this.properties = properties;
    }

    @Override
    public AuthResult authenticate(GatewayMessage message) {
        if (!(message.channelMetadata() instanceof ChannelMetadata.DingtalkMetadata meta)) {
            return AuthResult.failure("通道元数据类型不匹配: 期望 DingtalkMetadata");
        }

        // 时间戳容忍窗口检查
        long tolerance = properties.webhook().timestampToleranceSeconds() * 1000L;
        long now = System.currentTimeMillis();
        if (Math.abs(now - meta.timestamp()) > tolerance) {
            log.warn("钉钉签名验证失败: 时间戳过期, timestamp={}, tolerance={}ms", meta.timestamp(), tolerance);
            return AuthResult.failure("时间戳过期");
        }

        // HmacSHA256 签名验证
        var appSecret = properties.channels().dingtalk().appSecret();
        boolean valid = signatureVerifier.verify(meta.sign(), meta.timestamp(), appSecret);
        if (!valid) {
            log.warn("钉钉签名验证失败: sign={}", maskSensitive(meta.sign()));
            return AuthResult.failure("签名验证失败");
        }

        log.debug("钉钉认证通过: userId={}", message.userId());
        return AuthResult.success(message.userId(), TrustLevel.VERIFIED);
    }

    @Override
    public ChannelType supportedChannel() {
        return ChannelType.DINGTALK;
    }

    private static String maskSensitive(String value) {
        if (value == null || value.length() <= 8) return "***";
        return value.substring(0, 8) + "***";
    }
}
