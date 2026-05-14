package com.lifepilot.memory.consolidation.association;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AssociationCandidate 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class AssociationCandidate_单元测试 {

    @Test
    void 构造成功_字段完整() {
        var c = new AssociationCandidate(
                "e1", "e2", AssociationType.CAUSES, 0.8f, "evidence", "seed-1", Instant.EPOCH);
        assertThat(c.sourceEntityId()).isEqualTo("e1");
        assertThat(c.targetEntityId()).isEqualTo("e2");
        assertThat(c.relationType()).isEqualTo(AssociationType.CAUSES);
        assertThat(c.confidence()).isEqualTo(0.8f);
    }

    @Test
    void confidence_超出范围被clamp() {
        var c1 = new AssociationCandidate(
                "e1", "e2", AssociationType.RELATED_TO, 1.5f, null, "s", null);
        var c2 = new AssociationCandidate(
                "e1", "e2", AssociationType.RELATED_TO, -0.3f, null, "s", null);
        assertThat(c1.confidence()).isEqualTo(1.0f);
        assertThat(c2.confidence()).isEqualTo(0.0f);
    }

    @Test
    void generatedAt为null时用Instant_now填充() {
        var c = new AssociationCandidate(
                "e1", "e2", AssociationType.RELATED_TO, 0.5f, null, "s", null);
        assertThat(c.generatedAt()).isNotNull();
    }

    @Test
    void source_target_seed_为空抛异常() {
        assertThatThrownBy(() ->
                new AssociationCandidate(null, "e2", AssociationType.RELATED_TO, 0.5f, null, "s", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", null, AssociationType.RELATED_TO, 0.5f, null, "s", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2", null, 0.5f, null, "s", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2", AssociationType.RELATED_TO, 0.5f, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dedupKey格式() {
        var c = new AssociationCandidate(
                "a", "b", AssociationType.CAUSES, 0.5f, null, "s", null);
        assertThat(c.dedupKey()).isEqualTo("a:b:CAUSES");
    }
}
