package com.lifepilot.memory.eval.judge;

import com.lifepilot.memory.eval.loader.BenchmarkQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExactMatchJudge} 单元测试。
 *
 * <p>覆盖正例、反例、大小写、标点、中英混合、空值。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("ExactMatchJudge 单元测试")
class ExactMatchJudge测试 {

    private final ExactMatchJudge judge = new ExactMatchJudge();

    @Test
    @DisplayName("正例：完全一致 → 1")
    void 完全一致_正确() {
        JudgeVerdict v = judge.judge("oat milk latte", "oat milk latte", question());
        assertThat(v.score()).isEqualTo(1.0f);
        assertThat(v.skipped()).isFalse();
    }

    @Test
    @DisplayName("正例：大小写和标点差异 → 归一化后仍为 1")
    void 大小写标点差异_归一化为等价() {
        JudgeVerdict v = judge.judge("Oat Milk Latte.", "oat milk latte", question());
        assertThat(v.score()).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("中文标点归一化")
    void 中文标点_归一化() {
        JudgeVerdict v = judge.judge("北京。", "北京", question());
        assertThat(v.score()).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("反例：完全不同 → 0")
    void 完全不同_错误() {
        JudgeVerdict v = judge.judge("coffee", "tea", question());
        assertThat(v.score()).isEqualTo(0.0f);
    }

    @Test
    @DisplayName("边缘：两侧皆空 → 1")
    void 两侧皆空_视为正确() {
        JudgeVerdict v = judge.judge("", "", question());
        assertThat(v.score()).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("边缘：一侧为 null → 0")
    void 一侧为null_错误() {
        JudgeVerdict v = judge.judge(null, "tea", question());
        assertThat(v.score()).isEqualTo(0.0f);
    }

    private BenchmarkQuestion question() {
        return new BenchmarkQuestion("q-test", "问题", "ground",
                BenchmarkQuestion.QuestionType.SINGLE_HOP, List.of());
    }
}
