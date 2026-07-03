package com.lifepilot.memory.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RetrievalWeights 单元测试。
 *
 * @author zsg
 * @since 2026-06-30
 */
class RetrievalWeights_单元测试 {

    @Test
    void 权重为NaN时直接拒绝() {
        assertThatThrownBy(() -> new RetrievalWeights(Float.NaN, 0.3f, 0.7f, 0.05f, 0.1f, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("向量检索权重必须是非负有限数");
    }

    @Test
    void 权重为负数时直接拒绝() {
        assertThatThrownBy(() -> new RetrievalWeights(0.5f, -0.1f, 0.6f, 0.05f, 0.1f, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全文检索权重必须是非负有限数");
    }

    @Test
    void rrfK非正时直接拒绝() {
        assertThatThrownBy(() -> new RetrievalWeights(0.5f, 0.3f, 0.2f, 0.05f, 0.1f, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RRF K 必须大于 0");
    }

    @Test
    void 权重和不为1时直接拒绝() {
        assertThatThrownBy(() -> new RetrievalWeights(0.5f, 0.3f, 0.3f, 0.05f, 0.1f, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("检索权重之和必须为 1.0");
    }
}
