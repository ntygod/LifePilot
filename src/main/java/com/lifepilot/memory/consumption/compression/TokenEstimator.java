package com.lifepilot.memory.consumption.compression;

/**
 * Token 数量估算工具 — 上下文管理范围内的<b>唯一</b> Token 估算口径。
 *
 * <p>采用单一固定公式：按 UTF-16 code unit 逐字符计权，ASCII 字符（{@code c <= 0x7F}）
 * 按 0.25 Token、非 ASCII 字符（中文、日文、韩文、emoji 等，{@code c > 0x7F}）按 1.5 Token，
 * 四舍五入后取下限 1（非空文本至少 1 Token）。该分类互斥且完备，任意字符恰归一类，
 * 不漏算不重复计权。</p>
 *
 * <p>本类是上下文组装、压缩触发、热摘要截断、窗口核算等所有上下文管理路径共用的统一口径，
 * 不再在各组件内联各自的估算实现。非 ASCII 偏保守计权（1.5）以避免低估上下文窗口占用、
 * 使压缩触发不至偏晚。无状态纯函数，无需引入 tokenizer 依赖。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /**
     * 估算文本的 Token 数量。
     *
     * <p>契约：相同输入恒返回相同的非负整数；null 或空字符串返回 0；非空文本返回大于等于 1 的整数。</p>
     *
     * @param text 待估算文本，null 或空字符串返回 0
     * @return 估算的 Token 数量（{@code >= 0}；非空文本 {@code >= 1}）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        double tokens = 0.0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 互斥且完备的二分：ASCII 按 0.25 Token，非 ASCII 按 1.5 Token
            tokens += (c <= 0x7F) ? 0.25 : 1.5;
        }
        // 取下限 1：避免单个 ASCII 字符 round(0.25)=0 导致非空文本估算为 0
        return Math.max(1, (int) Math.round(tokens));
    }
}
