package com.lifepilot.knowledge.util;

import com.knuddels.jtokkit.api.Encoding;

/**
 * Token 计数策略接口 — 用于精确或估算计算文本 Token 数量。
 *
 * <p>sealed interface，允许两种实现：
 * <ul>
 *   <li>{@link Jtokkit} — 基于 jtokkit 的精确计数（OpenAI 系模型）</li>
 *   <li>{@link Heuristic} — 启发式估算兜底（非 OpenAI 模型或 jtokkit 不可用时）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-07
 */
public sealed interface TokenCounter permits TokenCounter.Jtokkit, TokenCounter.Heuristic {

    /**
     * 计算文本的 Token 数量。
     *
     * @param text 文本内容
     * @return Token 数
     */
    int countTokens(String text);

    /**
     * 基于 jtokkit 的精确 Token 计数器（兼容 tiktoken 编码）。
     *
     * <p>支持 cl100k_base（GPT-4/text-embedding-3）、o200k_base（GPT-4o）等编码。
     */
    record Jtokkit(Encoding encoding) implements TokenCounter {
        @Override
        public int countTokens(String text) {
            if (text == null || text.isEmpty()) return 0;
            return encoding.countTokens(text);
        }
    }

    /**
     * 启发式 Token 估算器 — 作为 jtokkit 不可用时的兜底策略。
     *
     * <p>中文字符按 1 Token/字符计算，其他字符按 4 字符/Token 计算。
     */
    record Heuristic() implements TokenCounter {
        @Override
        public int countTokens(String text) {
            return TextUtils.estimateTokens(text);
        }
    }
}
