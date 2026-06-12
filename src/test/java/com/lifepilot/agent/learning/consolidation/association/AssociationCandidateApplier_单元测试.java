package com.lifepilot.agent.learning.consolidation.association;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.memory.store.entity.SemanticMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssociationCandidateApplier 单元测试 —— 验证阈值过滤、缺失实体跳过、幂等去重、计数。
 *
 * @author zsg
 * @since 2026-06-06
 */
class AssociationCandidateApplier_单元测试 {

    private SemanticMemory semanticMemory;
    private AgentLearningProperties props;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        props = new AgentLearningProperties();
        props.getRem().setApplyMinConfidence(0.75f);
        // 默认：实体均存活、关系不存在
        when(semanticMemory.existsCurrentById(any())).thenReturn(true);
        when(semanticMemory.relationExists(any(), any(), any())).thenReturn(false);
    }

    private AssociationCandidate candidate(String src, String tgt, float conf) {
        return new AssociationCandidate(src, tgt, AssociationType.RELATED_TO, conf, "证据", "seed-1", Instant.now());
    }

    @Test
    void 高置信候选应写入关系(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        var today = LocalDate.now();
        store.save(today, List.of(candidate("e1", "e2", 0.9f)));
        var applier = new AssociationCandidateApplier(props, store, semanticMemory);

        var result = applier.apply(today);

        assertThat(result.applied()).isEqualTo(1);
        verify(semanticMemory, times(1)).addRelation(any(TemporalRelation.class));
    }

    @Test
    void 低置信候选被阈值过滤(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        var today = LocalDate.now();
        store.save(today, List.of(candidate("e1", "e2", 0.5f)));
        var applier = new AssociationCandidateApplier(props, store, semanticMemory);

        var result = applier.apply(today);

        assertThat(result.applied()).isZero();
        assertThat(result.skippedLowConfidence()).isEqualTo(1);
        verify(semanticMemory, never()).addRelation(any(TemporalRelation.class));
    }

    @Test
    void 端点实体缺失时跳过(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        var today = LocalDate.now();
        store.save(today, List.of(candidate("e1", "missing", 0.9f)));
        when(semanticMemory.existsCurrentById("missing")).thenReturn(false);
        var applier = new AssociationCandidateApplier(props, store, semanticMemory);

        var result = applier.apply(today);

        assertThat(result.applied()).isZero();
        assertThat(result.skippedMissingEntity()).isEqualTo(1);
        verify(semanticMemory, never()).addRelation(any(TemporalRelation.class));
    }

    @Test
    void 关系已存在时幂等跳过(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        var today = LocalDate.now();
        store.save(today, List.of(candidate("e1", "e2", 0.9f)));
        when(semanticMemory.relationExists(any(), any(), any())).thenReturn(true);
        var applier = new AssociationCandidateApplier(props, store, semanticMemory);

        var result = applier.apply(today);

        assertThat(result.applied()).isZero();
        assertThat(result.skippedDuplicate()).isEqualTo(1);
        verify(semanticMemory, never()).addRelation(any(TemporalRelation.class));
    }

    @Test
    void 无候选返回空统计(@TempDir Path tempDir) {
        var store = new AssociationCandidateStore(tempDir);
        var applier = new AssociationCandidateApplier(props, store, semanticMemory);

        var result = applier.apply(LocalDate.now());

        assertThat(result.input()).isZero();
        assertThat(result.applied()).isZero();
    }
}
