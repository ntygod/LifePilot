package com.lifepilot.interaction.channel.dingtalk;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉 HmacSHA256 签名验证工具。
 *
 * <p>签名算法：{@code Base64(HmacSHA256(timestamp + "\n" + appSecret, appSecret))}。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class DingtalkSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(DingtalkSignatureVerifier.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * 验证钉钉签名。
     *
     * @param sign      请求中的签名
     * @param timestamp 时间戳（毫秒）
     * @param appSecret 应用密钥
     * @return 签名是否匹配
     */
    public boolean verify(String sign, long timestamp, String appSecret) {
        if (sign == null || appSecret == null) {
            return false;
        }
        String computed = compute(timestamp, appSecret);
        return computed.equals(sign);
    }

    /**
     * 计算钉钉签名。
     *
     * @param timestamp 时间戳（毫秒）
     * @param appSecret 应用密钥
     * @return Base64 编码的 HmacSHA256 签名
     */
    public String compute(long timestamp, String appSecret) {
        try {
            String stringToSign = timestamp + "\n" + appSecret;
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signData);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 签名计算失败", e);
        }
    }
}
