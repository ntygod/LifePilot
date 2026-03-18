package com.lifepilot.memory.compression;

/**
 * Token 数量估算工具 — 区分中英文字符进行粗略 Token 估算。
 *
 * <p>中文字符按 1 字符 ≈ 1.5 Token 估算，ASCII 字符按 4 字符 ≈ 1 Token 估算。
 * 用于压缩服务等场景的 Token 预算判断，无需引入 tokenizer 依赖。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /**
     * 估算文本的 Token 数量。
     *
     * @param text 待估算文本，null 或空字符串返回 0
     * @return 估算的 Token 数量
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        float tokens = 0.0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x7F) {
                // 非 ASCII（中文、日文等）：1 字符 ≈ 1.5 Token
                tokens += 1.5f;
            } else {
                // ASCII 字符：4 字符 ≈ 1 Token
                tokens += 0.25f;
            }
        }
        return Math.round(tokens);
    }
}
