package com.lifepilot.knowledge.retrieve;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemorySpace;
import com.lifepilot.memory.store.scope.MemorySpaceKeys;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.memory.store.scope.MemorySpaceType;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
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
 * GraphKnowledgeSearcher 单元测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
@DisplayName("GraphKnowledgeSearcher 单元测试")
class GraphKnowledgeSearcher_单元测试 {

    @Test
    void 图命中应优先通过实体来源定位chunk() {
        var semanticMemory = mock(SemanticMemory.class);
        var chunkRepository = mock(DocumentChunkRepository.class);
        var memorySpaceRepository = mock(MemorySpaceRepository.class);
        var searcher = new GraphKnowledgeSearcher(
                semanticMemory, chunkRepository, memorySpaceRepository);

        var domainSpace = domainSpace("space-kb-1", "kb-1");
        when(memorySpaceRepository.findBySpaceKey(MemorySpaceKeys.knowledgeBaseDomain("kb-1")))
                .thenReturn(Optional.of(domainSpace));

        var expectedFilter = MemoryReadFilter.of(
                List.of("space-kb-1"),
                List.of(MemoryScope.DOMAIN_MEMORY)
        );
        var entity = entity("entity-a", "角色A", EntityType.PERSON);
        when(semanticMemory.findCurrentByNameAndType("角色A", EntityType.PERSON, expectedFilter))
                .thenReturn(Optional.of(entity));
        when(semanticMemory.findRelated("entity-a", 2)).thenReturn(List.of());
        when(semanticMemory.findSourceEntryIdsByEntityIds(any()))
                .thenReturn(Map.of("entity-a", List.of("chunk-1")));
        when(chunkRepository.findByIds(List.of("chunk-1"))).thenReturn(List.of(chunk("chunk-1", "doc-1", "kb-1")));

        var results = searcher.search("角色A", List.of(new KnowledgeSearchScope("kb-1")), 5);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().chunkId()).isEqualTo("chunk-1");
        assertThat(results.getFirst().sourcePath()).isEqualTo("graph");
        assertThat(results.getFirst().scoreBreakdown()).hasValueSatisfying(score ->
                assertThat(score.graphScore()).isGreaterThan(0.0));
        verify(semanticMemory).findCurrentByNameAndType(eq("角色A"), eq(EntityType.PERSON), eq(expectedFilter));
    }

    @Test
    void 指定知识库没有domainSpace时不应跨库匹配实体() {
        var semanticMemory = mock(SemanticMemory.class);
        var chunkRepository = mock(DocumentChunkRepository.class);
        var memorySpaceRepository = mock(MemorySpaceRepository.class);
        var searcher = new GraphKnowledgeSearcher(
                semanticMemory, chunkRepository, memorySpaceRepository);

        when(memorySpaceRepository.findBySpaceKey(MemorySpaceKeys.knowledgeBaseDomain("kb-missing")))
                .thenReturn(Optional.empty());

        var results = searcher.search("角色A", List.of(new KnowledgeSearchScope("kb-missing")), 5);

        assertThat(results).isEmpty();
        verifyNoInteractions(semanticMemory, chunkRepository);
    }

    @Test
    void 无chunk级来源时不应回退实体根文档() {
        var semanticMemory = mock(SemanticMemory.class);
        var chunkRepository = mock(DocumentChunkRepository.class);
        var memorySpaceRepository = mock(MemorySpaceRepository.class);
        var searcher = new GraphKnowledgeSearcher(
                semanticMemory, chunkRepository, memorySpaceRepository);

        when(memorySpaceRepository.findBySpaceKey(MemorySpaceKeys.knowledgeBaseDomain("kb-1")))
                .thenReturn(Optional.of(domainSpace("space-kb-1", "kb-1")));
        var expectedFilter = MemoryReadFilter.of(List.of("space-kb-1"), List.of(MemoryScope.DOMAIN_MEMORY));
        var entity = entity("entity-a", "角色A", EntityType.PERSON);
        when(semanticMemory.findCurrentByNameAndType("角色A", EntityType.PERSON, expectedFilter))
                .thenReturn(Optional.of(entity));
        when(semanticMemory.findRelated("entity-a", 2)).thenReturn(List.of());
        when(semanticMemory.findSourceEntryIdsByEntityIds(any())).thenReturn(Map.of());

        var results = searcher.search("角色A", List.of(new KnowledgeSearchScope("kb-1")), 5);

        assertThat(results).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    void 完全没有chunk级证据时返回空结果() {
        var semanticMemory = mock(SemanticMemory.class);
        var chunkRepository = mock(DocumentChunkRepository.class);
        var memorySpaceRepository = mock(MemorySpaceRepository.class);
        var searcher = new GraphKnowledgeSearcher(
                semanticMemory, chunkRepository, memorySpaceRepository);

        when(memorySpaceRepository.findBySpaceKey(MemorySpaceKeys.knowledgeBaseDomain("kb-1")))
                .thenReturn(Optional.of(domainSpace("space-kb-1", "kb-1")));
        var expectedFilter = MemoryReadFilter.of(List.of("space-kb-1"), List.of(MemoryScope.DOMAIN_MEMORY));
        var entity = entity("entity-a", "角色A", EntityType.PERSON);
        when(semanticMemory.findCurrentByNameAndType("角色A", EntityType.PERSON, expectedFilter))
                .thenReturn(Optional.of(entity));
        when(semanticMemory.findRelated("entity-a", 2)).thenReturn(List.of());
        when(semanticMemory.findSourceEntryIdsByEntityIds(any())).thenReturn(Map.of());

        var results = searcher.search("角色A", List.of(new KnowledgeSearchScope("kb-1")), 5);

        assertThat(results).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    private static TemporalEntity entity(String id, String name, EntityType type) {
        var now = Instant.now();
        return new TemporalEntity(
                id,
                type,
                name,
                name + " 的描述",
                Map.of(),
                1,
                true,
                now,
                null,
                "doc-1",
                0.9f,
                0.8f,
                0,
                null,
                now,
                now
        ,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                java.util.List.of(),
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                now);
    }

    private static DocumentChunk chunk(String id, String documentId, String knowledgeBaseId) {
        return new DocumentChunk(
                id,
                documentId,
                knowledgeBaseId,
                "角色A 的资料",
                Optional.empty(),
                0,
                0,
                6,
                6,
                "hash-" + id,
                List.of("设定"),
                0,
                Map.of(),
                DocumentSourceType.FILE,
                Optional.empty(),
                1
        );
    }

    private static MemorySpace domainSpace(String id, String knowledgeBaseId) {
        var now = Instant.now();
        return new MemorySpace(
                id,
                MemorySpaceKeys.knowledgeBaseDomain(knowledgeBaseId),
                MemorySpaceType.DOMAIN,
                "知识库领域记忆",
                "KNOWLEDGE_BASE",
                knowledgeBaseId,
                Map.of("knowledgeBaseId", knowledgeBaseId),
                now,
                now
        );
    }
}
