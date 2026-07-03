package com.lifepilot.memory.retrieval.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvidenceBundle / EvidenceItem 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class EvidenceBundle_单元测试 {

    @Test
    void EvidenceItem_score为负数或NaN时抛异常() {
        assertThatThrownBy(() -> new EvidenceItem("e1", "GOAL", "n", "d",
                -0.1f, "hybrid", 0.5f, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score 必须是非负有限数");
        assertThatThrownBy(() -> new EvidenceItem("e1", "GOAL", "n", "d",
                Float.NaN, "hybrid", 0.5f, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score 必须是非负有限数");
    }

    @Test
    void EvidenceItem_confidence越界时抛异常() {
        assertThatThrownBy(() -> new EvidenceItem("e1", "GOAL", "n", "d",
                1.5f, "hybrid", -0.3f, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence 必须在 [0,1] 范围内");
    }

    @Test
    void EvidenceItem_null_entityId抛异常() {
        assertThatThrownBy(() -> new EvidenceItem(null, "GOAL", "n", "d",
                0.5f, "hybrid", 0.5f, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void EvidenceItem_null_sourcePath抛异常() {
        assertThatThrownBy(() -> new EvidenceItem("e1", "GOAL", "n", "d",
                0.5f, null, 0.5f, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void EvidenceItem_null_entityType抛异常() {
        assertThatThrownBy(() -> new EvidenceItem("e1", null, "n", "d",
                0.5f, "hybrid", 0.5f, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityType 不能为空");
    }

    @Test
    void EvidenceItem_null_name抛异常() {
        assertThatThrownBy(() -> new EvidenceItem("e1", "GOAL", null, "d",
                0.5f, "hybrid", 0.5f, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name 不能为空");
    }

    @Test
    void EvidenceBundle_empty_便捷构造() {
        var b = EvidenceBundle.empty("test");
        assertThat(b.query()).isEqualTo("test");
        assertThat(b.items()).isEmpty();
        assertThat(b.sources()).isEmpty();
        assertThat(b.latencyMs()).isEqualTo(0L);
    }

    @Test
    void EvidenceBundle_null字段抛异常() {
        assertThatThrownBy(() -> new EvidenceBundle(null, "GENERAL", List.of(), 0L, Set.of(), Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query 不能为空");
        assertThatThrownBy(() -> new EvidenceBundle("q", null, List.of(), 0L, Set.of(), Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("strategy 不能为空");
        assertThatThrownBy(() -> new EvidenceBundle("q", "GENERAL", null, 0L, Set.of(), Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("items 不能为空");
    }

    @Test
    void EvidenceBundle_latency为负数时抛异常() {
        assertThatThrownBy(() -> new EvidenceBundle("q", "GENERAL", List.of(), -5L, Set.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("检索耗时不能为负数");
    }

    @Test
    void EvidenceBundle_items不可变() {
        var item = new EvidenceItem("e1", "GOAL", "n", "d",
                0.5f, "hybrid", 0.5f, Map.of());
        var b = new EvidenceBundle("q", "GENERAL", List.of(item), 10L, Set.of("hybrid"), Map.of());
        assertThatThrownBy(() -> b.items().add(item))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
