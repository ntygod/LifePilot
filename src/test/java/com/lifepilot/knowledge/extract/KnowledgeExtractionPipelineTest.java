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
    void 文件文档写入知识库领域空间() {
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

        var domainSpace = new com.lifepilot.memory.scope.MemorySpace(
                "space-kb-1",
                "domain:knowledge-base:kb-1",
                MemorySpaceType.DOMAIN,
                "知识库领域记忆",
                "KNOWLEDGE_BASE",
                "kb-1",
                Map.of("knowledgeBaseId", "kb-1"),
                Instant.now(),
                Instant.now()
        );
        when(memorySpaceRepository.ensureKnowledgeBaseDomainSpace("kb-1")).thenReturn(domainSpace);
        when(promptRegistry.render(eq("knowledge/entity-extraction"), any(Map.class))).thenReturn("prompt");
        when(generationRouter.callEntity(
                eq("knowledge_extraction"),
                eq("prompt"),
                eq(KnowledgeExtractionPipeline.ExtractionResponse.class),
                eq(null),
                eq(null),
                eq(null)
        )).thenReturn(new KnowledgeExtractionPipeline.ExtractionResponse(List.of(), List.of()));

        var result = pipeline.extract(doc, chunks);

        assertThat(result.entityCount()).isZero();
        assertThat(result.relationCount()).isZero();
        assertThat(result.warnings()).isEmpty();
        verifyNoInteractions(semanticMemory);
    }

    @Test
    void 关系解析应按当前知识库领域空间过滤同名实体() {
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
                "space-kb-1",
                "domain:knowledge-base:kb-1",
                MemorySpaceType.DOMAIN,
                "知识库领域记忆",
                "KNOWLEDGE_BASE",
                "kb-1",
                Map.of("knowledgeBaseId", "kb-1"),
                Instant.now(),
                Instant.now()
        );
        when(memorySpaceRepository.ensureKnowledgeBaseDomainSpace("kb-1")).thenReturn(domainSpace);
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
                List.of("space-kb-1"),
                List.of(MemoryScope.DOMAIN_MEMORY)
        );
        when(semanticMemory.findCurrentByNameAndType(eq("角色A"), any(EntityType.class), any(MemoryReadFilter.class)))
                .thenReturn(Optional.of(buildEntity("entity-a", "角色A")));
        when(semanticMemory.findCurrentByNameAndType(eq("角色B"), any(EntityType.class), any(MemoryReadFilter.class)))
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
        verify(semanticMemory).findCurrentByNameAndType(eq("角色A"), any(EntityType.class), eq(expectedFilter));
        verify(semanticMemory).findCurrentByNameAndType(eq("角色B"), any(EntityType.class), eq(expectedFilter));
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
