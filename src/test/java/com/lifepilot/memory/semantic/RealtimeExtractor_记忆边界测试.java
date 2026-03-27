package com.lifepilot.memory.semantic;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RealtimeExtractor 记忆边界测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
@DisplayName("RealtimeExtractor 记忆边界测试")
class RealtimeExtractor_记忆边界测试 {

    @Test
    void 知识域轮次默认不应自动学习个人记忆() {
        var generationRouter = mock(GenerationRouter.class);
        var semanticMemory = mock(SemanticMemory.class);
        var extractionValidator = mock(ExtractionValidator.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var promptRegistry = mock(PromptRegistry.class);
        var snapshotRepository = mock(ChatTurnMemorySnapshotRepository.class);
        var properties = new MemoryProperties();

        var extractor = new RealtimeExtractor(
                generationRouter,
                semanticMemory,
                properties,
                extractionValidator,
                jdbcTemplate,
                promptRegistry,
                snapshotRepository
        );

        when(snapshotRepository.findByTurnId("turn-domain")).thenReturn(Optional.of(
                new ChatTurnMemorySnapshot(
                        "turn-domain",
                        "session-domain",
                        "memory-space-personal-default",
                        "memory-space-experience-default",
                        null,
                        List.of("memory-space-personal-default", "memory-space-experience-default"),
                        List.of("kb-1"),
                        List.of("ds-1"),
                        false,
                        false,
                        true,
                        Map.of("source", "session_config"),
                        Instant.now()
                )
        ));

        extractor.extract("session-domain", "turn-domain", "继续写小说设定", "好的，我继续扩展世界观");

        verifyNoInteractions(generationRouter);
        verifyNoInteractions(semanticMemory);
    }
}
