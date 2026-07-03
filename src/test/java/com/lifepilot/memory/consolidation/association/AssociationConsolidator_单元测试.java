package com.lifepilot.memory.consolidation.association;

import com.lifepilot.agent.learning.consolidation.association.AssociationCandidate;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateStore;
import com.lifepilot.agent.learning.consolidation.association.AssociationConsolidator;
import com.lifepilot.agent.learning.consolidation.association.AssociationType;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AssociationConsolidator 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class AssociationConsolidator_单元测试 {

    @Test
    void 置信度低于阈值被过滤(@TempDir Path tempDir) {
        var props = new AgentLearningProperties();
        props.getRem().setMinConfidence(0.65f);
        var store = new AssociationCandidateStore(tempDir);
        var consolidator = new AssociationConsolidator(props, store);

        var result = consolidator.consolidate(List.of(
                cand("a", "b", AssociationType.CAUSES, 0.5f),  // 低于阈值
                cand("c", "d", AssociationType.SIMILAR_TO, 0.9f)
        ));

        assertThat(result).isEqualTo(1);
        assertThat(store.load(LocalDate.now())).hasSize(1);
        assertThat(store.load(LocalDate.now()).get(0).sourceEntityId()).isEqualTo("c");
    }

    @Test
    void 去重窗口内同source_target_type不重复入库(@TempDir Path tempDir) {
        var props = new AgentLearningProperties();
        props.getRem().setMinConfidence(0.5f);
        props.getRem().setDeduplicationWindowHours(24);
        var store = new AssociationCandidateStore(tempDir);
        var consolidator = new AssociationConsolidator(props, store);

        int first = consolidator.consolidate(List.of(
                cand("a", "b", AssociationType.CAUSES, 0.9f)));
        int second = consolidator.consolidate(List.of(
                cand("a", "b", AssociationType.CAUSES, 0.9f)));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
    }

    @Test
    void 不同关系类型不算重复(@TempDir Path tempDir) {
        var props = new AgentLearningProperties();
        var store = new AssociationCandidateStore(tempDir);
        var consolidator = new AssociationConsolidator(props, store);

        int n = consolidator.consolidate(List.of(
                cand("a", "b", AssociationType.CAUSES, 0.9f),
                cand("a", "b", AssociationType.SIMILAR_TO, 0.9f)
        ));
        assertThat(n).isEqualTo(2);
    }

    @Test
    void 空输入返回0(@TempDir Path tempDir) {
        var props = new AgentLearningProperties();
        var store = new AssociationCandidateStore(tempDir);
        var consolidator = new AssociationConsolidator(props, store);

        assertThat(consolidator.consolidate(List.of())).isEqualTo(0);
    }

    @Test
    void null候选列表应暴露调用错误(@TempDir Path tempDir) {
        var props = new AgentLearningProperties();
        var store = new AssociationCandidateStore(tempDir);
        var consolidator = new AssociationConsolidator(props, store);

        assertThatThrownBy(() -> consolidator.consolidate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("REM 联想候选列表不能为空");
    }

    @Test
    void 非法去重窗口应暴露配置错误(@TempDir Path tempDir) {
        var props = new AgentLearningProperties();
        props.getRem().setDeduplicationWindowHours(0);
        var store = new AssociationCandidateStore(tempDir);
        var consolidator = new AssociationConsolidator(props, store);

        assertThatThrownBy(() -> consolidator.consolidate(List.of(
                cand("a", "b", AssociationType.CAUSES, 0.9f))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REM 去重窗口小时数必须大于 0");
    }

    private AssociationCandidate cand(String src, String tgt, AssociationType type, float conf) {
        return new AssociationCandidate(src, tgt, type, conf, null, "seed-x", Instant.now());
    }
}
