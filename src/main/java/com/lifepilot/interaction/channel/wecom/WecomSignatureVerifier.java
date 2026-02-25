package com.lifepilot.interaction.channel.wecom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企业微信 SHA1 签名验证工具。
 *
 * <p>签名算法：{@code SHA1(sort(token, timestamp, nonce, encrypt))}。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WecomSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WecomSignatureVerifier.class);

    private final String token;

    public WecomSignatureVerifier(String token) {
        this.token = token;
    }

    /**
     * 计算签名。
     *
     * @param timestamp 时间戳
     * @param nonce     随机数
     * @param encrypt   加密消息（可为空字符串）
     * @return SHA1 签名的十六进制字符串
     */
    public String compute(String timestamp, String nonce, String encrypt) {
        String[] arr = {token, timestamp, nonce, encrypt};
        Arrays.sort(arr);
        String joined = String.join("", arr);
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 算法不可用", e);
        }
    }

    /**
     * 验证签名。
     *
     * @param msgSignature 请求中的签名
     * @param timestamp    时间戳
     * @param nonce        随机数
     * @param encrypt      加密消息
     * @return 签名是否匹配
     */
    public boolean verify(String msgSignature, String timestamp, String nonce, String encrypt) {
        if (msgSignature == null || timestamp == null || nonce == null) {
            return false;
        }
        String computed = compute(timestamp, nonce, encrypt != null ? encrypt : "");
        return computed.equals(msgSignature);
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
