package com.lifepilot.knowledge.extract;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * KnowledgeExtractionPipeline 测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
@DisplayName("KnowledgeExtractionPipeline 测试")
class KnowledgeExtractionPipelineTest {

    @Test
    void 共享文件文档默认不应写入长期记忆() {
        var generationRouter = mock(GenerationRouter.class);
        var semanticMemory = mock(SemanticMemory.class);
        var promptRegistry = mock(PromptRegistry.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var memorySpaceRepository = new MemorySpaceRepository(jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());
        var extraction = new KnowledgeBaseProperties.Extraction(true, 4);

        var pipeline = new KnowledgeExtractionPipeline(
                generationRouter,
                semanticMemory,
                extraction,
                promptRegistry,
                memorySpaceRepository
        );

        var doc = new Document(
                "doc-shared",
                "kb-1",
                "shared.md",
                "/tmp/shared.md",
                12,
                "text/markdown",
                "hash",
                com.lifepilot.knowledge.model.DocumentStatus.READY,
                0,
                0,
                null,
                null,
                Map.of(),
                Instant.now(),
                Instant.now(),
                DocumentSourceType.FILE,
                "FILE:doc-shared",
                null,
                null,
                Map.of()
        );
        var chunks = List.of(new DocumentChunk(
                "chunk-1",
                "doc-shared",
                "kb-1",
                "主角设定",
                java.util.Optional.empty(),
                0,
                4,
                0,
                4,
                "hash-chunk",
                List.of(),
                0,
                Map.of()
        ));

        var result = pipeline.extract(doc, chunks);

        assertThat(result.entityCount()).isZero();
        assertThat(result.relationCount()).isZero();
        assertThat(result.warnings()).contains("当前文档默认不写入长期记忆");
        verifyNoInteractions(generationRouter);
        verifyNoInteractions(semanticMemory);
    }
}
