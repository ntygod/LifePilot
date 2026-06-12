package com.lifepilot.memory.consumption.compression;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.NotEmpty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TokenEstimator 属性测试 —— 验证统一 Token 估算口径的契约与字符分类完备性。
 *
 * @author zsg
 * @since 2026-03-18
 */
class TokenEstimator属性测试 {

    // Feature: context-management-optimization, Property 1: Token 估算契约
    // （确定性、非负、空输入归零、非空文本至少 1）

    @Property(tries = 200)
    void 任意文本多次估算结果一致(@ForAll String text) {
        int first = TokenEstimator.estimate(text);
        int second = TokenEstimator.estimate(text);
        assertEquals(first, second, "相同输入多次调用应返回相同值");
    }

    @Property(tries = 200)
    void 估算值恒非负(@ForAll String text) {
        assertTrue(TokenEstimator.estimate(text) >= 0, "估算值应 >= 0");
    }

    @Property(tries = 200)
    void 非空文本估算至少为1(@ForAll @NotEmpty String text) {
        assertTrue(TokenEstimator.estimate(text) >= 1, "非空文本估算应 >= 1");
    }

    @Test
    void 空与null返回0() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    // Feature: context-management-optimization, Property 2: 字符分类完备且加权可加
    // （每字符按 c<=0x7F 互斥归类，加权和 = ASCII 数×0.25 + 非 ASCII 数×1.5）

    @Property(tries = 200)
    void 估算等于字符分类加权和(@ForAll String text) {
        int expected = expectedEstimate(text);
        assertEquals(expected, TokenEstimator.estimate(text),
                "估算应等于 ASCII×0.25 + 非ASCII×1.5 四舍五入后取下限 1（非空）");
    }

    @Property(tries = 200)
    void 字符级加权可加(@ForAll String a, @ForAll String b) {
        // round/下限前的字符级加权贡献满足可加：weight(a)+weight(b)==weight(a+b)
        assertEquals(weight(a) + weight(b), weight(a + b), 1e-9,
                "字符级加权贡献应可加");
    }

    /** 参考实现：与 TokenEstimator.estimate 同口径的独立计算，用于交叉验证。 */
    private static int expectedEstimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.round(weight(text)));
    }

    private static double weight(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }
        double tokens = 0.0;
        for (int i = 0; i < text.length(); i++) {
            tokens += (text.charAt(i) <= 0x7F) ? 0.25 : 1.5;
        }
        return tokens;
    }
}
