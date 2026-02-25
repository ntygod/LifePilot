package com.lifepilot.interaction.channel.feishu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 飞书事件回调 AES-256-CBC 解密工具。
 *
 * <p>密钥从 {@code encryptKey} 经 SHA-256 派生。
 * 加密格式：{@code Base64(IV(16) + AES-CBC(plainText))}。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class FeishuCrypto {

    private static final String AES_CBC_NOPADDING = "AES/CBC/NoPadding";

    private final byte[] aesKey;

    /**
     * 构造飞书解密工具。
     *
     * @param encryptKey 飞书后台配置的 Encrypt Key
     */
    public FeishuCrypto(String encryptKey) {
        try {
            this.aesKey = MessageDigest.getInstance("SHA-256")
                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 解密飞书加密事件。
     *
     * @param encryptedText Base64 编码的密文（前 16 字节为 IV）
     * @return 解密后的明文 JSON 字符串
     */
    public String decrypt(String encryptedText) {
        try {
            byte[] encrypted = Base64.getDecoder().decode(encryptedText);
            byte[] iv = Arrays.copyOfRange(encrypted, 0, 16);
            byte[] cipherText = Arrays.copyOfRange(encrypted, 16, encrypted.length);

            Cipher cipher = Cipher.getInstance(AES_CBC_NOPADDING);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(cipherText);

            // 去除 PKCS#7 填充
            byte[] unpadded = pkcs7Unpad(decrypted);
            return new String(unpadded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("飞书事件解密失败", e);
        }
    }

    /**
     * 加密明文（用于测试 round-trip）。
     *
     * @param plainText 明文字符串
     * @return Base64 编码的密文
     */
    public String encrypt(String plainText) {
        try {
            byte[] textBytes = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] padded = pkcs7Pad(textBytes, 16);

            // 生成随机 IV
            byte[] iv = new byte[16];
            new java.security.SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_CBC_NOPADDING);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(padded);

            // IV + 密文
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException("飞书事件加密失败", e);
        }
    }

    private static byte[] pkcs7Pad(byte[] data, int blockSize) {
        int padding = blockSize - (data.length % blockSize);
        byte[] padded = Arrays.copyOf(data, data.length + padding);
        Arrays.fill(padded, data.length, padded.length, (byte) padding);
        return padded;
    }

    private static byte[] pkcs7Unpad(byte[] data) {
        int padding = data[data.length - 1] & 0xFF;
        if (padding < 1 || padding > 16) {
            return data;
        }
        return Arrays.copyOf(data, data.length - padding);
    }
}
