package com.lifepilot.memory.consolidation.association;

import com.lifepilot.agent.learning.consolidation.association.AssociationCandidate;
import com.lifepilot.agent.learning.consolidation.association.AssociationType;
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
    void confidence_超出范围直接抛异常() {
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2", AssociationType.RELATED_TO, 1.5f, null, "s", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence 必须在 [0,1] 范围内");
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2", AssociationType.RELATED_TO, -0.3f, null, "s", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence 必须在 [0,1] 范围内");
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2", AssociationType.RELATED_TO, Float.NaN, null, "s", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence 必须在 [0,1] 范围内");
    }

    @Test
    void generatedAt为null时直接抛异常() {
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2", AssociationType.RELATED_TO, 0.5f, null, "s", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generatedAt 不能为空");
    }

    @Test
    void source_target_seed_为空抛异常() {
        assertThatThrownBy(() ->
                new AssociationCandidate(null, "e2", AssociationType.RELATED_TO, 0.5f, null, "s", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", null, AssociationType.RELATED_TO, 0.5f, null, "s", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2", null, 0.5f, null, "s", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2", AssociationType.RELATED_TO, 0.5f, null, null, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void source_target_seed_含首尾空白抛异常() {
        assertThatThrownBy(() ->
                new AssociationCandidate(" e1", "e2", AssociationType.RELATED_TO, 0.5f, null, "s", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceEntityId 不能包含首尾空白");
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2 ", AssociationType.RELATED_TO, 0.5f, null, "s", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetEntityId 不能包含首尾空白");
        assertThatThrownBy(() ->
                new AssociationCandidate("e1", "e2", AssociationType.RELATED_TO, 0.5f, null, " s", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seedEntityId 不能包含首尾空白");
    }

    @Test
    void dedupKey格式() {
        var c = new AssociationCandidate(
                "a", "b", AssociationType.CAUSES, 0.5f, null, "s", Instant.EPOCH);
        assertThat(c.dedupKey()).isEqualTo("a:b:CAUSES");
    }
}
