package com.lifepilot.interaction.channel.wecom;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 企业微信消息 AES-256-CBC 加解密工具。
 *
 * <p>密钥从 {@code EncodingAESKey} Base64 解码获得（43 字符 + "=" 补齐后解码为 32 字节）。
 * IV 取密钥前 16 字节。加密格式：{@code Base64(AES(random(16) + networkOrder(len) + plainText + corpId))}。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WecomCrypto {

    private static final String AES_CBC_PKCS5 = "AES/CBC/NoPadding";
    private static final int RANDOM_BYTES_LENGTH = 16;

    private final byte[] aesKey;
    private final byte[] iv;
    private final String corpId;
    private final SecureRandom random = new SecureRandom();

    /**
     * 构造企微加解密工具。
     *
     * @param encodingAesKey 企微后台配置的 EncodingAESKey（43 字符）
     * @param corpId         企业 ID
     */
    public WecomCrypto(String encodingAesKey, String corpId) {
        this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        this.iv = Arrays.copyOfRange(this.aesKey, 0, 16);
        this.corpId = corpId;
    }

    /**
     * 加密明文。
     *
     * @param plainText 明文字符串
     * @return Base64 编码的密文
     */
    public String encrypt(String plainText) {
        try {
            byte[] textBytes = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] corpIdBytes = corpId.getBytes(StandardCharsets.UTF_8);

            // random(16) + networkOrder(len) + plainText + corpId
            byte[] networkOrder = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(textBytes.length)
                    .array();

            byte[] randomBytes = new byte[RANDOM_BYTES_LENGTH];
            random.nextBytes(randomBytes);

            byte[] unpadded = concat(randomBytes, networkOrder, textBytes, corpIdBytes);
            byte[] padded = pkcs7Pad(unpadded, 32);

            Cipher cipher = Cipher.getInstance(AES_CBC_PKCS5);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(padded);
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("企微消息加密失败", e);
        }
    }

    /**
     * 解密密文。
     *
     * @param encryptedText Base64 编码的密文
     * @return 解密后的明文字符串
     */
    public String decrypt(String encryptedText) {
        try {
            byte[] encrypted = Base64.getDecoder().decode(encryptedText);

            Cipher cipher = Cipher.getInstance(AES_CBC_PKCS5);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(encrypted);

            // 去除 PKCS#7 填充
            byte[] unpadded = pkcs7Unpad(decrypted);

            // 跳过 random(16)，读取 networkOrder(4) 获取明文长度
            int textLength = ByteBuffer.wrap(unpadded, RANDOM_BYTES_LENGTH, 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .getInt();

            return new String(unpadded, RANDOM_BYTES_LENGTH + 4, textLength, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("企微消息解密失败", e);
        }
    }

    // ── PKCS#7 填充 ──────────────────────────────────────────

    private static byte[] pkcs7Pad(byte[] data, int blockSize) {
        int padding = blockSize - (data.length % blockSize);
        byte[] padded = Arrays.copyOf(data, data.length + padding);
        Arrays.fill(padded, data.length, padded.length, (byte) padding);
        return padded;
    }

    private static byte[] pkcs7Unpad(byte[] data) {
        int padding = data[data.length - 1] & 0xFF;
        if (padding < 1 || padding > 32) {
            return data;
        }
        return Arrays.copyOf(data, data.length - padding);
    }

    private static byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] arr : arrays) {
            totalLength += arr.length;
        }
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] arr : arrays) {
            System.arraycopy(arr, 0, result, offset, arr.length);
            offset += arr.length;
        }
        return result;
    }
}
