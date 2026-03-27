package com.lifepilot.knowledge.extract;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.scope.MemorySpaceType;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        var memorySpaceRepository = mock(MemorySpaceRepository.class);
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

    @Test
    void 关系解析应按当前领域空间过滤同名实体() {
        var generationRouter = mock(GenerationRouter.class);
        var semanticMemory = mock(SemanticMemory.class);
        var promptRegistry = mock(PromptRegistry.class);
        var memorySpaceRepository = mock(MemorySpaceRepository.class);
        var extraction = new KnowledgeBaseProperties.Extraction(true, 4);

        var pipeline = new KnowledgeExtractionPipeline(
                generationRouter,
                semanticMemory,
                extraction,
                promptRegistry,
                memorySpaceRepository
        );

        var domainSpace = new com.lifepilot.memory.scope.MemorySpace(
                "space-ds-1",
                "domain:datastore:ds-1",
                MemorySpaceType.DOMAIN,
                "Datastore领域记忆",
                "DATASTORE",
                "ds-1",
                Map.of("datastoreId", "ds-1"),
                Instant.now(),
                Instant.now()
        );
        when(memorySpaceRepository.ensureDatastoreDomainSpace("ds-1")).thenReturn(domainSpace);
        when(promptRegistry.render(eq("knowledge/entity-extraction"), any(Map.class))).thenReturn("prompt");
        when(generationRouter.callEntity(
                eq("knowledge_extraction"),
                eq("prompt"),
                eq(KnowledgeExtractionPipeline.ExtractionResponse.class),
                eq(null),
                eq(null),
                eq(null)
        )).thenReturn(new KnowledgeExtractionPipeline.ExtractionResponse(
                List.of(),
                List.of(new KnowledgeExtractionPipeline.ExtractionResponse.RelationInfo("角色A", "角色B", "KNOWS", 0.8f))
        ));

        MemoryReadFilter expectedFilter = MemoryReadFilter.of(
                List.of("space-ds-1"),
                List.of(MemoryScope.DOMAIN_MEMORY)
        );
        when(semanticMemory.findCurrentByNameAndType(eq("角色A"), eq(EntityType.PERSON), eq(expectedFilter)))
                .thenReturn(Optional.of(buildEntity("entity-a", "角色A")));
        when(semanticMemory.findCurrentByNameAndType(eq("角色B"), eq(EntityType.PERSON), eq(expectedFilter)))
                .thenReturn(Optional.of(buildEntity("entity-b", "角色B")));

        var doc = new Document(
                "doc-domain",
                "kb-1",
                "domain.md",
                "/tmp/domain.md",
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
                "FILE:doc-domain",
                "ds-1",
                null,
                Map.of()
        );
        var chunks = List.of(new DocumentChunk(
                "chunk-domain",
                "doc-domain",
                "kb-1",
                "角色A 认识 角色B",
                java.util.Optional.empty(),
                0,
                8,
                0,
                8,
                "hash-domain",
                List.of(),
                0,
                Map.of()
        ));

        var result = pipeline.extract(doc, chunks);

        assertThat(result.relationCount()).isEqualTo(1);
        verify(semanticMemory).findCurrentByNameAndType("角色A", EntityType.PERSON, expectedFilter);
        verify(semanticMemory).findCurrentByNameAndType("角色B", EntityType.PERSON, expectedFilter);
    }

    private TemporalEntity buildEntity(String id, String name) {
        Instant now = Instant.now();
        return new TemporalEntity(
                id,
                EntityType.PERSON,
                name,
                name + " 的描述",
                Map.of(),
                1,
                true,
                now,
                null,
                null,
                0.9f,
                0.5f,
                0,
                null,
                now,
                now
        );
    }
}
