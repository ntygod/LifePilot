package com.lifepilot.agent.learning.extraction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DueDateExtractor 单元测试 —— 绝对日期确定性提取与归一。
 *
 * @author zsg
 * @since 2026-06-07
 */
class DueDateExtractor_单元测试 {

    @Test
    void ISO日期() {
        assertThat(DueDateExtractor.extractIsoDate("截止 2026-06-13 前完成", 2026))
                .contains("2026-06-13");
        assertThat(DueDateExtractor.extractIsoDate("deadline 2026/6/9", 2026))
                .contains("2026-06-09");
    }

    @Test
    void 中文完整日期() {
        assertThat(DueDateExtractor.extractIsoDate("硬性截止日期 2026年6月14日，不能拖", 2026))
                .contains("2026-06-14");
        assertThat(DueDateExtractor.extractIsoDate("2026年12月1号交", 2026))
                .contains("2026-12-01");
    }

    @Test
    void 无年份中文日期补参考年() {
        assertThat(DueDateExtractor.extractIsoDate("6月13日前交合同", 2026))
                .contains("2026-06-13");
    }

    @Test
    void 非法日期返回空() {
        assertThat(DueDateExtractor.extractIsoDate("2026-13-40 不合法", 2026)).isEmpty();
    }

    @Test
    void 无日期返回空() {
        assertThat(DueDateExtractor.extractIsoDate("我喜欢喝咖啡", 2026)).isEmpty();
        assertThat(DueDateExtractor.extractIsoDate(null, 2026)).isEmpty();
        assertThat(DueDateExtractor.extractIsoDate("  ", 2026)).isEmpty();
    }
}
