package com.lifepilot.agent.learning.extraction;

import com.lifepilot.agent.learning.extraction.ExtractionValidator;
import com.lifepilot.agent.learning.extraction.RealtimeExtractor;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.governance.security.InjectionDetectionResult;
import com.lifepilot.memory.governance.security.MemoryInjectionDetector;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RealtimeExtractor 项目隔离对话学习路由测试 —— 验证快照的 projectSpaceId
 * 会作为 personalWriteContext.spaceId 传入 SemanticMemory.upsertWithConflictDetection。
 *
 * <p>不模拟 LLM 详细响应，而是让 router 返回一条 ADD 决策，用 Mockito 捕获 writeContext。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class RealtimeExtractor_项目隔离写入项目Space测试 {

    private GenerationRouter generationRouter;
    private SemanticMemory semanticMemory;
    private ChatTurnMemorySnapshotRepository snapshotRepository;
    private MemoryExtractionCandidateRepository candidateRepository;
    private JdbcTemplate jdbcTemplate;
    private RealtimeExtractor extractor;

    @BeforeEach
    void setUp() {
        generationRouter = mock(GenerationRouter.class);
        semanticMemory = mock(SemanticMemory.class);
        // AUDN 路径会调用 SemanticMemory.findAllCurrent 构建 existingSummary
        when(semanticMemory.findAllCurrent(any())).thenReturn(List.of());
        // upsertWithConflictDetection 直接返回原 entity，避免 null
        when(semanticMemory.upsertWithConflictDetection(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        snapshotRepository = mock(ChatTurnMemorySnapshotRepository.class);

        PromptRegistry promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(any(), any())).thenReturn("prompt-ignored");

        AgentLearningProperties props = new AgentLearningProperties();
        ExtractionValidator validator = new ExtractionValidator(props);

        jdbcTemplate = mock(JdbcTemplate.class);
        candidateRepository = mock(MemoryExtractionCandidateRepository.class);
        when(candidateRepository.recordValidated(any(), any(), any())).thenReturn("candidate-id");
        var injectionDetector = mock(MemoryInjectionDetector.class);
        when(injectionDetector.detect(any(), any(), anyFloat())).thenReturn(InjectionDetectionResult.pass());
        var relationExtractionStep = mock(RelationExtractionStep.class);

        String audnJson = """
                [{"operation":"ADD","entityName":"喜欢咖啡","entityType":"PREFERENCE",
                  "description":"喜欢咖啡",
                  "extractionConfidence":0.8,"importanceScore":0.5,"evidenceKind":"USER_EXPLICIT",
                  "evidenceExcerpt":"我喜欢咖啡","temporality":"PERSISTENT"}]
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new LlmResponse(audnJson, null, null, List.of(), Map.of(), 1, 1, null, 0, "mock", "mock", 1L, false));

        extractor = new RealtimeExtractor(
                generationRouter, semanticMemory, props, validator,
                jdbcTemplate, promptRegistry, snapshotRepository,
                Clock.systemUTC(), new MemoryAccessPolicy(),
                candidateRepository, injectionDetector, relationExtractionStep);
    }

    @Test
    void 隔离项目对话_写入使用项目spaceId() {
        String turnId = "turn-iso-1";
        String projectSpaceId = "space-project-x";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, "session-iso", projectSpaceId)));

        extractor.extract("session-iso", turnId, "我喜欢咖啡", "好的");

        var captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(semanticMemory).upsertWithConflictDetection(any(), any(), captor.capture());
        MemoryWriteContext ctx = captor.getValue();
        assertThat(ctx.spaceId()).isEqualTo(projectSpaceId);
        assertThat(ctx.memoryScope()).isNull();  // 不硬编码 scope，由 SemanticMemory 按 entity type 推断
        verify(jdbcTemplate, times(1)).update(contains("INSERT INTO extraction_event_log"), any(Object[].class));
    }

    @Test
    void 非隔离项目或主账户对话_写入spaceId为null() {
        String turnId = "turn-main";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, "session-main", null)));

        extractor.extract("session-main", turnId, "我喜欢咖啡", "好的");

        var captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(semanticMemory).upsertWithConflictDetection(any(), any(), captor.capture());
        assertThat(captor.getValue().spaceId()).isNull();
        assertThat(captor.getValue().memoryScope()).isNull();
        verify(jdbcTemplate, times(1)).update(contains("INSERT INTO extraction_event_log"), any(Object[].class));
    }

    @Test
    void 提取事件日志写入失败时应直接暴露错误() {
        String turnId = "turn-log-fail";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, "session-main", null)));
        doThrow(new IllegalStateException("事件日志写入失败"))
                .when(jdbcTemplate).update(contains("INSERT INTO extraction_event_log"), any(Object[].class));

        assertThatThrownBy(() -> extractor.extract("session-main", turnId, "我喜欢咖啡", "好的"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("事件日志写入失败");
    }

    @Test
    void 快照缺失_应直接暴露治理链路错误() {
        String turnId = "turn-no-snapshot";
        when(snapshotRepository.findByTurnId(turnId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> extractor.extract("session-no-snapshot", turnId, "我喜欢咖啡", "好的"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实时实体提取缺少轮次作用域快照")
                .hasMessageContaining(turnId);

        verify(semanticMemory, never()).upsertWithConflictDetection(any(), any(), any());
    }

    @Test
    void 快照会话不匹配_应直接失败() {
        String turnId = "turn-session-mismatch";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, "other-session", null)));

        assertThatThrownBy(() -> extractor.extract("session-main", turnId, "我喜欢咖啡", "好的"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("轮次作用域快照会话不匹配")
                .hasMessageContaining("other-session");

        verify(semanticMemory, never()).upsertWithConflictDetection(any(), any(), any());
    }

    @Test
    void 自动学习明确禁用_应跳过写入() {
        String turnId = "turn-learning-disabled";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newDisabledSnapshot(turnId, "session-disabled")));

        extractor.extract("session-disabled", turnId, "我喜欢咖啡", "好的");

        verify(generationRouter, never()).call(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(semanticMemory, never()).upsertWithConflictDetection(any(), any(), any());
    }

    @Test
    void markdown包裹Audn响应_按非契约输出失败() {
        String turnId = "turn-markdown-json";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, "session-main", null)));
        String fencedJson = """
                ```json
                [{"operation":"ADD","entityName":"喜欢咖啡","entityType":"PREFERENCE",
                  "description":"喜欢咖啡","extractionConfidence":0.8,"importanceScore":0.5,
                  "evidenceKind":"USER_EXPLICIT","evidenceExcerpt":"我喜欢咖啡","temporality":"PERSISTENT"}]
                ```
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new LlmResponse(fencedJson, null, null, List.of(), Map.of(),
                        1, 1, null, 0, "mock", "mock", 1L, false));

        assertThatThrownBy(() -> extractor.extract("session-main", turnId, "我喜欢咖啡", "好的"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUDN 数组解析失败");

        verify(semanticMemory, never()).upsertWithConflictDetection(any(), any(), any());
    }

    @Test
    void 空Audn响应_应直接失败() {
        String turnId = "turn-blank-audn";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, "session-main", null)));
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new LlmResponse("  ", null, null, List.of(), Map.of(),
                        1, 1, null, 0, "mock", "mock", 1L, false));

        assertThatThrownBy(() -> extractor.extract("session-main", turnId, "我喜欢咖啡", "好的"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUDN 实体提取 LLM 返回空内容");

        verify(semanticMemory, never()).upsertWithConflictDetection(any(), any(), any());
        verify(candidateRepository, never()).recordValidated(any(), any(), any());
    }

    @Test
    void Audn调用失败_应抛错且不伪装为空结果() {
        String turnId = "turn-llm-fail";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, "session-main", null)));
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("LLM 故障"));

        assertThatThrownBy(() -> extractor.extract("session-main", turnId, "我喜欢咖啡", "好的"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUDN 实体提取 LLM 调用失败")
                .hasMessageContaining("LLM 故障");

        verify(semanticMemory, never()).upsertWithConflictDetection(any(), any(), any());
        verify(candidateRepository, never()).recordValidated(any(), any(), any());
        verify(candidateRepository, never()).recordRejected(any(), any(), any(), any());
    }

    @Test
    void update不存在实体_应抛错且不降级新增() {
        String turnId = "turn-update-missing";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, "session-main", null)));
        when(semanticMemory.findCurrentByNameAndType(any(), any(), any()))
                .thenReturn(Optional.empty());
        String updateJson = """
                [{"operation":"UPDATE","entityName":"不存在偏好","entityType":"PREFERENCE",
                  "description":"不存在偏好",
                  "extractionConfidence":0.8,"importanceScore":0.5,
                  "evidenceKind":"USER_EXPLICIT","evidenceExcerpt":"不存在偏好","temporality":"PERSISTENT"}]
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new LlmResponse(updateJson, null, null, List.of(), Map.of(),
                        1, 1, null, 0, "mock", "mock", 1L, false));

        assertThatThrownBy(() -> extractor.extract("session-main", turnId, "不存在偏好", "好的"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUDN UPDATE 未找到已有实体");

        verify(semanticMemory, never()).upsertWithConflictDetection(any(), any(), any());
        verify(candidateRepository).markFailed(eq("candidate-id"), contains("AUDN UPDATE 未找到已有实体"));
    }

    @Test
    void ADD持久化返回null_应直接失败() {
        String turnId = "turn-add-null";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, "session-main", null)));
        when(semanticMemory.upsertWithConflictDetection(any(), any(), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> extractor.extract("session-main", turnId, "我喜欢咖啡", "好的"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("AUDN ADD 持久化返回为空");

        verify(candidateRepository).markFailed(eq("candidate-id"), contains("AUDN ADD 持久化返回为空"));
    }

    private ChatTurnMemorySnapshot newSnapshot(String turnId, String sessionId, String projectSpaceId) {
        return new ChatTurnMemorySnapshot(
                turnId,
                sessionId,
                "personal-1",
                "experience-1",
                null,
                projectSpaceId,
                List.of("personal-1", "experience-1"),
                List.of(),
                true,  // personalLearningEnabled
                false,
                true,
                Map.of(),
                Instant.parse("2026-04-23T00:00:00Z")
        );
    }

    private ChatTurnMemorySnapshot newDisabledSnapshot(String turnId, String sessionId) {
        return new ChatTurnMemorySnapshot(
                turnId,
                sessionId,
                "personal-1",
                "experience-1",
                null,
                null,
                List.of("personal-1", "experience-1"),
                List.of(),
                false,
                false,
                false,
                Map.of(),
                Instant.parse("2026-04-23T00:00:00Z")
        );
    }
}
