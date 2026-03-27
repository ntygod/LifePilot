package com.lifepilot.memory.semantic;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void 领域学习更新应只查询当前领域空间中的实体() {
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
                        "space-domain-1",
                        List.of("memory-space-personal-default", "memory-space-experience-default", "space-domain-1"),
                        List.of("kb-1"),
                        List.of("ds-1"),
                        false,
                        true,
                        true,
                        Map.of("source", "session_config"),
                        Instant.now()
                )
        ));
        when(promptRegistry.render(eq("semantic/entity-extraction"), any(Map.class))).thenReturn("prompt");
        when(generationRouter.call(
                eq("knowledge_extraction"),
                eq("prompt"),
                eq(null),
                eq(null),
                eq(null),
                eq(com.lifepilot.modelservice.model.GenerationCapability.CHAT),
                any(Duration.class)
        )).thenReturn(new com.lifepilot.llm.LlmResponse(
                """
                {"decisions":[{"operation":"UPDATE","entityName":"沈星河","entityType":"PERSON","description":"主角补充设定","properties":{},"extractionConfidence":0.9,"importanceScore":0.8}]}
                """,
                0,
                0,
                "test-provider",
                "test-model",
                0L,
                false
        ));
        when(extractionValidator.validate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(semanticMemory.findAllCurrent(eq(MemoryReadFilter.of(List.of("space-domain-1"), List.of(MemoryScope.DOMAIN_MEMORY)))))
                .thenReturn(List.of());

        extractor.extract("session-domain", "turn-domain", "继续补全主角设定", "好的，我补全背景与能力");

        verify(semanticMemory).findCurrentByNameAndType(
                "沈星河",
                EntityType.PERSON,
                MemoryReadFilter.of(List.of("space-domain-1"), List.of(MemoryScope.DOMAIN_MEMORY))
        );
    }
}
