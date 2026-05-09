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
    void EvidenceItem_clamp_score_和_confidence() {
        var item = new EvidenceItem("e1", "GOAL", "n", "d",
                1.5f, "hybrid", -0.3f, Map.of());
        assertThat(item.score()).isEqualTo(1.0f);
        assertThat(item.confidence()).isEqualTo(0.0f);
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
    void EvidenceBundle_empty_便捷构造() {
        var b = EvidenceBundle.empty("test");
        assertThat(b.query()).isEqualTo("test");
        assertThat(b.items()).isEmpty();
        assertThat(b.sources()).isEmpty();
        assertThat(b.latencyMs()).isEqualTo(0L);
    }

    @Test
    void EvidenceBundle_null参数安全默认() {
        var b = new EvidenceBundle(null, null, null, -5L, null, null);
        assertThat(b.query()).isEqualTo("");
        assertThat(b.strategy()).isEqualTo("GENERAL");
        assertThat(b.items()).isEmpty();
        assertThat(b.sources()).isEmpty();
        assertThat(b.metadata()).isEmpty();
        assertThat(b.latencyMs()).isEqualTo(0L);
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
