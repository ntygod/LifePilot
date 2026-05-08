package com.lifepilot.memory.eval.probe;

import com.lifepilot.memory.compression.TokenEstimator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 估算采集器。
 *
 * <p>基于既有 {@link TokenEstimator}，记录每次召回结果拼进 prompt 的 token 数。
 * 累计并计算平均值。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class TokenProbe {

    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicInteger recallCount = new AtomicInteger();

    /**
     * 记录一次召回的 token 消耗。
     * @param injectedText 实际拼入 prompt 的文本；为 null/空 时记 0
     */
    public void record(String injectedText) {
        int tokens = TokenEstimator.estimate(injectedText);
        totalTokens.addAndGet(tokens);
        recallCount.incrementAndGet();
    }

    public long totalTokens() {
        return totalTokens.get();
    }

    public int recallCount() {
        return recallCount.get();
    }

    public int averageTokensPerRecall() {
        int n = recallCount.get();
        return n == 0 ? 0 : (int) (totalTokens.get() / n);
    }

    public void reset() {
        totalTokens.set(0);
        recallCount.set(0);
    }
}
