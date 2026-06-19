package com.lifepilot.agent.learning.extraction;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RealtimeExtractor 对话期关系抽取测试 —— 验证实体写入后关系经 addRelation 落库、
 * 开关与去重生效。
 *
 * @author zsg
 * @since 2026-06-06
 */
class RealtimeExtractor_关系抽取测试 {

    private GenerationRouter generationRouter;
    private SemanticMemory semanticMemory;
    private ChatTurnMemorySnapshotRepository snapshotRepository;
    private RelationExtractionStep relationExtractionStep;
    private AgentLearningProperties props;

    private static final String AUDN_JSON = """
            [{"operation":"ADD","entityName":"张三","entityType":"PERSON","description":"用户同事",
              "extractionConfidence":0.8,"importanceScore":0.6,"evidenceKind":"USER_EXPLICIT","evidenceExcerpt":"张三是用户同事"},
             {"operation":"ADD","entityName":"阿里","entityType":"ORGANIZATION","description":"公司",
              "extractionConfidence":0.8,"importanceScore":0.6,"evidenceKind":"USER_EXPLICIT","evidenceExcerpt":"阿里是公司"}]
            """;

    @BeforeEach
    void setUp() {
        generationRouter = mock(GenerationRouter.class);
        semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.findAllCurrent(any())).thenReturn(List.of());
        when(semanticMemory.upsertWithConflictDetection(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(semanticMemory.relationExists(any(), any(), any())).thenReturn(false);

        snapshotRepository = mock(ChatTurnMemorySnapshotRepository.class);
        relationExtractionStep = mock(RelationExtractionStep.class);

        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new LlmResponse(AUDN_JSON, null, null, List.of(), Map.of(), 1, 1, null, 0, "mock", "mock", 1L, false));

        props = new AgentLearningProperties();

        rebuildExtractor();
    }

    private RealtimeExtractor extractor;

    private void rebuildExtractor() {
        var promptRegistry = mock(com.lifepilot.prompt.PromptRegistry.class);
        when(promptRegistry.render(any(), any())).thenReturn("prompt-ignored");
        var validator = mock(ExtractionValidator.class);
        when(validator.validate(any())).thenAnswer(inv -> inv.getArgument(0));
        extractor = new RealtimeExtractor(
                generationRouter, semanticMemory, props, validator,
                mock(JdbcTemplate.class), promptRegistry, snapshotRepository,
                null, null, null, null, relationExtractionStep);
    }

    private ChatTurnMemorySnapshot snapshot(String turnId) {
        return new ChatTurnMemorySnapshot(
                turnId, "session-x", "personal-1", "experience-1", null, null,
                List.of("personal-1", "experience-1"), List.of(),
                true, false, true, Map.of(),
                Instant.parse("2026-06-06T00:00:00Z"));
    }

    @Test
    void 实体写入后关系应经addRelation落库() {
        String turnId = "turn-rel-1";
        when(snapshotRepository.findByTurnId(turnId)).thenReturn(Optional.of(snapshot(turnId)));
        when(relationExtractionStep.extract(any(), any())).thenReturn(List.of(
                new RelationExtractionStep.ExtractedRelation("张三", "阿里", "就职于", 0.9f, "证据")));

        extractor.extract("session-rel", turnId, "张三在阿里工作", "好的");

        var captor = ArgumentCaptor.forClass(TemporalRelation.class);
        verify(semanticMemory).addRelation(captor.capture(), any());
        TemporalRelation r = captor.getValue();
        assertThat(r.relationType()).isEqualTo("就职于");
        assertThat(r.sourceEntityId()).isNotBlank();
        assertThat(r.targetEntityId()).isNotBlank();
        assertThat(r.sourceEntityId()).isNotEqualTo(r.targetEntityId());
    }

    @Test
    void 关闭关系抽取开关时不调用关系步骤也不写关系() {
        props.getExtraction().setRelationExtractionEnabled(false);
        rebuildExtractor();
        String turnId = "turn-rel-2";
        when(snapshotRepository.findByTurnId(turnId)).thenReturn(Optional.of(snapshot(turnId)));

        extractor.extract("session-rel", turnId, "张三在阿里工作", "好的");

        verify(relationExtractionStep, never()).extract(any(), any());
        verify(semanticMemory, never()).addRelation(any(), any());
    }

    @Test
    void 关系已存在时去重不重复写入() {
        String turnId = "turn-rel-3";
        when(snapshotRepository.findByTurnId(turnId)).thenReturn(Optional.of(snapshot(turnId)));
        when(semanticMemory.relationExists(any(), any(), any())).thenReturn(true);
        when(relationExtractionStep.extract(any(), any())).thenReturn(List.of(
                new RelationExtractionStep.ExtractedRelation("张三", "阿里", "就职于", 0.9f, "证据")));

        extractor.extract("session-rel", turnId, "张三在阿里工作", "好的");

        verify(semanticMemory, never()).addRelation(any(), any());
    }

    @Test
    void 端点无法解析时跳过关系() {
        String turnId = "turn-rel-4";
        when(snapshotRepository.findByTurnId(turnId)).thenReturn(Optional.of(snapshot(turnId)));
        when(relationExtractionStep.extract(any(), any())).thenReturn(List.of(
                new RelationExtractionStep.ExtractedRelation("未知实体", "另一个未知", "相关", 0.7f, "x")));

        extractor.extract("session-rel", turnId, "张三在阿里工作", "好的");

        verify(semanticMemory, never()).addRelation(any(), any());
    }
}
