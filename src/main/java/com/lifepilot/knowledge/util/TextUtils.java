package com.lifepilot.knowledge.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文本处理工具类 — 提供 Token 估算、SHA-256 哈希、字数统计等共享方法。
 *
 * <p>统一替代各模块中重复的 estimateTokens / sha256 / estimateWordCount 实现。
 *
 * @author zsg
 * @since 2026-04-07
 */
public final class TextUtils {

    private TextUtils() {}

    /**
     * 估算文本的 Token 数量。
     *
     * <p>中文字符按 1 Token/字符计算，其他字符按 4 字符/Token 计算。
     * 该方法为启发式估算，后续可替换为真实 Tokenizer（如 jtokkit）。
     *
     * @param text 文本内容
     * @return 估算 Token 数
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long chineseChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - chineseChars;
        return (int) (chineseChars + otherChars / 4);
    }

    /**
     * 计算文本的 SHA-256 哈希值。
     *
     * @param text 文本内容
     * @return 小写十六进制哈希字符串（64 字符）
     */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，不应发生
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 估算文本字数：中文按字符数计算，英文按空格分词计算。
     *
     * @param text 文本内容
     * @return 估算字数
     */
    public static long estimateWordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long chineseChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long totalWords = 0;
        for (String word : text.split("\\s+")) {
            if (!word.isEmpty()) {
                totalWords++;
            }
        }
        // 中文字符 + 英文单词（减去中文字符已计入的部分）
        return chineseChars + Math.max(0, totalWords - chineseChars);
    }

    /**
     * 判断文本是否包含 CJK（中日韩）字符。
     *
     * @param text 文本内容
     * @return 包含 CJK 字符返回 true
     */
    public static boolean containsCjk(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.chars().anyMatch(c ->
                Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }
}
