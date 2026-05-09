package com.lifepilot.memory.eval.judge;

import com.lifepilot.memory.eval.loader.BenchmarkQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link F1Judge} 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("F1Judge 单元测试")
class F1Judge测试 {

    private final F1Judge judge = new F1Judge();

    @Test
    @DisplayName("完全一致 → F1 = 1")
    void 完全一致() {
        JudgeVerdict v = judge.judge("the quick brown fox", "the quick brown fox",
                question());
        assertThat(v.score()).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("部分重合 → F1 为 (0, 1)")
    void 部分重合() {
        // 停用词剥离后：pred=["quick","brown","fox"], truth=["quick","brown","dog"]
        JudgeVerdict v = judge.judge("the quick brown fox", "the quick brown dog",
                question());
        assertThat(v.score()).isGreaterThan(0f).isLessThan(1f);
        // overlap=2, precision=2/3, recall=2/3 → F1=2/3
        assertThat(v.score()).isEqualTo(2f / 3f);
    }

    @Test
    @DisplayName("完全不同 → F1 = 0")
    void 完全不同() {
        JudgeVerdict v = judge.judge("apple banana", "car bus", question());
        assertThat(v.score()).isEqualTo(0.0f);
    }

    @Test
    @DisplayName("中文按字切分: 'Max 是只金毛' vs '金毛' 有重合")
    void 中英混合分词() {
        JudgeVerdict v = judge.judge("Max 是只金毛", "金毛", question());
        assertThat(v.score()).isGreaterThan(0f);
    }

    @Test
    @DisplayName("两侧皆空 → F1 = 1")
    void 两侧皆空() {
        assertThat(judge.judge("", "", question()).score()).isEqualTo(1.0f);
    }

    private BenchmarkQuestion question() {
        return new BenchmarkQuestion("q", "问题", "ground",
                BenchmarkQuestion.QuestionType.MULTI_HOP, List.of());
    }
}
