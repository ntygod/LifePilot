package com.lifepilot.memory.retrieval;

import com.lifepilot.memory.config.MemoryProperties;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryRefiner 属性测试 — 验证输出长度不变量和填充词移除。
 *
 * @author zsg
 * @since 2026-03-20
 */
class QueryRefiner属性测试 {

    // ─────────────────────────────────────────────
    //  Property P1 — QueryRefiner 输出长度不变量
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 8.6</b>
     *
     * <p>对任意随机中文/英文/混合字符串，refine() 输出长度 ≤ queryMaxLength。</p>
     */
    @Property(tries = 100)
    void refine输出长度不超过queryMaxLength(@ForAll("randomStrings") String input) {
        var properties = buildProperties(100);
        var refiner = new QueryRefiner(properties);

        String output = refiner.refine(input);

        assertTrue(output.length() <= properties.getRetrieval().getQueryMaxLength(),
                "refine() 输出长度 %d 超过 queryMaxLength %d, input='%s', output='%s'"
                        .formatted(output.length(), properties.getRetrieval().getQueryMaxLength(),
                                truncate(input, 50), truncate(output, 50)));
    }

    /**
     * <b>Validates: Requirements 8.6</b>
     *
     * <p>使用较小的 queryMaxLength 验证截断行为。</p>
     */
    @Property(tries = 100)
    void refine输出长度不超过较小的queryMaxLength(@ForAll("longStrings") String input) {
        var properties = buildProperties(30);
        var refiner = new QueryRefiner(properties);

        String output = refiner.refine(input);

        assertTrue(output.length() <= 30,
                "refine() 输出长度 %d 超过 queryMaxLength 30, input长度=%d"
                        .formatted(output.length(), input.length()));
    }

    // ─────────────────────────────────────────────
    //  Property P2 — QueryRefiner 填充词移除
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 8.6</b>
     *
     * <p>对包含随机填充词的字符串，refine() 输出不包含已知填充词且长度 ≤ 输入长度。</p>
     */
    @Property(tries = 100)
    void refine移除填充词且长度不增(@ForAll("stringsWithFillers") String input) {
        var properties = buildProperties(500);
        // queryMinLength 设为 1 确保不跳过精炼
        properties.getRetrieval().setQueryMinLength(1);
        var refiner = new QueryRefiner(properties);

        String output = refiner.refine(input);

        // 输出长度不超过输入长度
        assertTrue(output.length() <= input.length(),
                "refine() 输出长度 %d 超过输入长度 %d"
                        .formatted(output.length(), input.length()));

        // 输出不包含已知填充词
        for (var filler : KNOWN_FILLERS) {
            assertFalse(output.contains(filler),
                    "refine() 输出仍包含填充词 '%s', output='%s'"
                            .formatted(filler, truncate(output, 100)));
        }
    }

    // ─────────────────────────────────────────────
    //  已知填充词列表
    // ─────────────────────────────────────────────

    private static final String[] KNOWN_FILLERS = {
            "嗯", "啊", "呃", "额", "哦", "那个", "就是说", "然后呢",
            "对吧", "你知道吗", "怎么说呢", "反正就是", "我觉得吧"
    };

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成随机中文/英文/混合字符串。 */
    @Provide
    Arbitrary<String> randomStrings() {
        var chinese = Arbitraries.strings()
                .withCharRange('一', '龥')
                .ofMinLength(1).ofMaxLength(200);
        var english = Arbitraries.strings()
                .alpha()
                .ofMinLength(1).ofMaxLength(200);
        var mixed = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('一', '龥')
                .ofMinLength(1).ofMaxLength(200);
        return Arbitraries.oneOf(chinese, english, mixed)
                .filter(s -> !s.isBlank());
    }

    /** 生成较长的字符串（确保超过 queryMaxLength）。 */
    @Provide
    Arbitrary<String> longStrings() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('一', '龥')
                .ofMinLength(50).ofMaxLength(300)
                .filter(s -> !s.isBlank());
    }

    /** 生成包含随机填充词的字符串。 */
    @Provide
    Arbitrary<String> stringsWithFillers() {
        var fillerArb = Arbitraries.of(KNOWN_FILLERS);
        var textArb = Arbitraries.strings()
                .withCharRange('一', '龥')
                .ofMinLength(5).ofMaxLength(50);

        return Combinators.combine(textArb, fillerArb, textArb)
                .as((prefix, filler, suffix) -> prefix + filler + suffix)
                .filter(s -> !s.isBlank());
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private MemoryProperties buildProperties(int queryMaxLength) {
        var properties = new MemoryProperties();
        properties.getRetrieval().setQueryMaxLength(queryMaxLength);
        properties.getRetrieval().setQueryMinLength(10);
        return properties;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
