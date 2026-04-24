package com.lifepilot.memory.semantic;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.prompt.PromptRegistry;
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

        ExtractionValidator validator = mock(ExtractionValidator.class);
        when(validator.validate(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        String audnJson = """
                [{"operation":"ADD","entityName":"喜欢咖啡","entityType":"PREFERENCE",
                  "description":"",
                  "extractionConfidence":0.8,"importanceScore":0.5}]
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new LlmResponse(audnJson, 1, 1, "mock", "mock", 1L, false));

        MemoryProperties props = new MemoryProperties();
        extractor = new RealtimeExtractor(
                generationRouter, semanticMemory, props, validator,
                jdbcTemplate, promptRegistry, snapshotRepository);
    }

    @Test
    void 隔离项目对话_写入使用项目spaceId() {
        String turnId = "turn-iso-1";
        String projectSpaceId = "space-project-x";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, projectSpaceId)));

        extractor.extract("session-iso", turnId, "我喜欢咖啡", "好的");

        var captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(semanticMemory).upsertWithConflictDetection(any(), any(), captor.capture());
        MemoryWriteContext ctx = captor.getValue();
        assertThat(ctx.spaceId()).isEqualTo(projectSpaceId);
        assertThat(ctx.memoryScope()).isNull();  // 不硬编码 scope，由 SemanticMemory 按 entity type 推断
    }

    @Test
    void 非隔离项目或主账户对话_写入spaceId为null() {
        String turnId = "turn-main";
        when(snapshotRepository.findByTurnId(turnId))
                .thenReturn(Optional.of(newSnapshot(turnId, null)));

        extractor.extract("session-main", turnId, "我喜欢咖啡", "好的");

        var captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(semanticMemory).upsertWithConflictDetection(any(), any(), captor.capture());
        assertThat(captor.getValue().spaceId()).isNull();
        assertThat(captor.getValue().memoryScope()).isNull();
    }

    @Test
    void 快照缺失_回退spaceId为null() {
        String turnId = "turn-no-snapshot";
        when(snapshotRepository.findByTurnId(turnId)).thenReturn(Optional.empty());

        extractor.extract("session-no-snapshot", turnId, "我喜欢咖啡", "好的");

        var captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
        verify(semanticMemory).upsertWithConflictDetection(any(), any(), captor.capture());
        assertThat(captor.getValue().spaceId()).isNull();
    }

    private ChatTurnMemorySnapshot newSnapshot(String turnId, String projectSpaceId) {
        return new ChatTurnMemorySnapshot(
                turnId,
                "session-x",
                "personal-1",
                "experience-1",
                null,
                projectSpaceId,
                List.of("personal-1", "experience-1"),
                List.of(),
                List.of(),
                true,  // personalLearningEnabled
                false,
                true,
                Map.of(),
                Instant.parse("2026-04-23T00:00:00Z")
        );
    }
}
