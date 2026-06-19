package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.MemoryProvenanceSummaryDto;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository.EntityMetadata;
import com.lifepilot.agent.learning.consolidation.ConsolidationPipeline;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.agent.learning.forgetting.ForgettingLogRepository;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MemoryController 单元测试。
 *
 * @author zsg
 * @since 2026-03-13
 */
@ExtendWith(MockitoExtension.class)
class MemoryControllerTest {

    @Mock private SemanticMemory semanticMemory;
    @Mock private EpisodicMemory episodicMemory;
    @Mock private ProceduralMemory proceduralMemory;
    @Mock private HybridRetriever hybridRetriever;
    @Mock private ConsolidationPipeline consolidationPipeline;
    @Mock private ForgettingLogRepository forgettingLogRepository;
    @Mock private MemoryProvenanceRepository provenanceRepository;
    @Mock private ProjectContextResolver projectContextResolver;

    private MockMvc mockMvc;

    private static final Instant NOW = Instant.parse("2026-03-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        lenient().when(projectContextResolver.resolve(null))
                .thenReturn(ProjectContext.personal("space-personal", "space-experience"));
        var controller = new MemoryController(
                semanticMemory, episodicMemory, proceduralMemory,
                hybridRetriever, consolidationPipeline, null, null, null, forgettingLogRepository,
                provenanceRepository, projectContextResolver, new MemoryAccessPolicy(), null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(provenanceRepository.loadEntityMetadata(anyCollection()))
                .thenReturn(Map.of());
        lenient().when(provenanceRepository.findEntityIdsByProvenanceFilters(any(), any(), any()))
                .thenReturn(null);
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private static TemporalEntity testEntity(String id, String name, EntityType type) {
        return new TemporalEntity(
                id, type, name, name + " 描述",
                Map.of(), 1, true, NOW, null, "conv-1",
                0.9f, 0.5f, 3, NOW, NOW, NOW)
                .withQuality(MemoryEvidenceKind.USER_EXPLICIT, MemoryTrustLevel.EXPLICIT, 0.9f, 1, NOW);
    }

    private static TemporalRelation testRelation(String id, String sourceId, String targetId) {
        return new TemporalRelation(
                id, sourceId, targetId, "KNOWS", 0.8f,
                null, NOW, null, "conv-1", NOW);
    }

    private static ConversationRecord testConversation(String id) {
        return new ConversationRecord(
                id, "session-1", "测试目标", "测试摘要",
                List.of(), NOW, NOW);
    }

    private void stubReadable(TemporalEntity entity) {
        when(semanticMemory.findByIds(eq(List.of(entity.id())), any(MemoryReadFilter.class)))
                .thenReturn(Map.of(entity.id(), entity));
    }

    // ── 记忆系统未启用 ───────────────────────────────────

    @Nested
    class 记忆系统未启用 {

        private MockMvc disabledMvc;

        @BeforeEach
        void setUp() {
            var controller = new MemoryController(
                    null, null, null, null, null, null, null, null, forgettingLogRepository,
                    provenanceRepository, projectContextResolver, new MemoryAccessPolicy(), null, null);
            disabledMvc = MockMvcBuilders.standaloneSetup(controller).build();
        }

        @Test
        void stats端点_返回503() throws Exception {
            disabledMvc.perform(get("/api/memories/stats"))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test
        void search端点_返回503() throws Exception {
            disabledMvc.perform(get("/api/memories/search").param("q", "test"))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test
        void entities端点_返回503() throws Exception {
            disabledMvc.perform(get("/api/memories/entities"))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test
        void consolidate端点_返回503() throws Exception {
            disabledMvc.perform(post("/api/memories/consolidate"))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    // ── 统计概览 ─────────────────────────────────────────

    @Nested
    class 统计概览 {

        @Test
        void 返回各层记忆统计() throws Exception {
            when(semanticMemory.countCurrent()).thenReturn(5L);
            when(semanticMemory.countCurrentRelations()).thenReturn(3L);
            when(semanticMemory.countCurrentByType()).thenReturn(Map.of("PERSON", 1L, "PROJECT", 1L));
            when(episodicMemory.countConversations()).thenReturn(10L);
            when(proceduralMemory.listAllTemplates()).thenReturn(List.of());
            when(proceduralMemory.listAllPreferences()).thenReturn(List.of());
            when(forgettingLogRepository.countAll()).thenReturn(2L);
            when(forgettingLogRepository.getLastForgettingTime()).thenReturn("2026-03-12T08:00:00Z");

            mockMvc.perform(get("/api/memories/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.conversationCount").value(10))
                    .andExpect(jsonPath("$.data.entityCount").value(5))
                    .andExpect(jsonPath("$.data.relationCount").value(3))
                    .andExpect(jsonPath("$.data.forgettingLogCount").value(2))
                    .andExpect(jsonPath("$.data.entityCountByType.PERSON").value(1))
                    .andExpect(jsonPath("$.data.entityCountByType.PROJECT").value(1));
        }
    }

    // ── 统一搜索 ─────────────────────────────────────────

    @Nested
    class 统一搜索 {

        @Test
        void 搜索返回结果() throws Exception {
            var result = new RetrievalResult(
                    "e1", "PERSON", "张三", "描述", 0.85f,
                    new RetrievalResult.ScoreBreakdown(0.5f, 0.3f, 0.3f, 0.2f, 0.2f, 0.1f, 0.05f, 0.05f, 0f, 0f),
                    "vector+fts", NOW, 0.5f, null, false, false, false);
            when(hybridRetriever.retrieve(eq("张三"), eq(30), any(RetrievalWeights.class), any()))
                    .thenReturn(List.of(result));
            when(semanticMemory.findByIds(anyCollection(), any(MemoryReadFilter.class)))
                    .thenReturn(Map.of("e1", testEntity("e1", "张三", EntityType.PERSON)));
            when(provenanceRepository.loadEntityMetadata(List.of("e1")))
                    .thenReturn(Map.of("e1", new EntityMetadata("e1", "user:default", "USER_FACT", "REAL")));

            mockMvc.perform(get("/api/memories/search").param("q", "张三"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.count").value(1))
                    .andExpect(jsonPath("$.data.results", hasSize(1)))
                    .andExpect(jsonPath("$.data.results[0].entityId").value("e1"))
                    .andExpect(jsonPath("$.data.results[0].name").value("张三"))
                    .andExpect(jsonPath("$.data.results[0].spaceId").value("user:default"))
                    .andExpect(jsonPath("$.data.results[0].memoryScope").value("USER_FACT"))
                    .andExpect(jsonPath("$.data.results[0].realityType").value("REAL"))
                    .andExpect(jsonPath("$.data.results[0].evidenceKind").value("USER_EXPLICIT"))
                    .andExpect(jsonPath("$.data.results[0].trustLevel").value("EXPLICIT"));
        }

        @Test
        void 搜索_隔离项目视图_通过访问策略构造filter() throws Exception {
            ProjectContextResolver resolver = mock(ProjectContextResolver.class);
            when(resolver.resolve("p-1")).thenReturn(
                    new ProjectContext("p-1", "space-project", "space-personal", "space-experience", true));
            var controller = new MemoryController(
                    semanticMemory, episodicMemory, proceduralMemory,
                    hybridRetriever, consolidationPipeline, null, null, null, forgettingLogRepository,
                    provenanceRepository, resolver, new MemoryAccessPolicy(), null, null);
            var projectMvc = MockMvcBuilders.standaloneSetup(controller).build();
            when(hybridRetriever.retrieve(eq("咖啡"), eq(30), any(RetrievalWeights.class), any()))
                    .thenReturn(List.of());

            projectMvc.perform(get("/api/memories/search")
                            .param("q", "咖啡")
                            .param("projectId", "p-1"))
                    .andExpect(status().isOk());

            ArgumentCaptor<MemoryReadFilter> captor = ArgumentCaptor.forClass(MemoryReadFilter.class);
            verify(hybridRetriever).retrieve(eq("咖啡"), eq(30), any(RetrievalWeights.class), captor.capture());
            assertThat(captor.getValue().spaceIds())
                    .containsExactlyInAnyOrder("space-project", "space-personal", "space-experience");
            assertThat(captor.getValue().scopes())
                    .containsExactlyInAnyOrder(MemoryScope.USER_PROFILE, MemoryScope.USER_FACT);
        }

        @Test
        void 空关键词_返回400() throws Exception {
            mockMvc.perform(get("/api/memories/search").param("q", ""))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── L3 实体管理 ──────────────────────────────────────

    @Nested
    class L3实体管理 {

        @Test
        void 实体列表_默认分页() throws Exception {
            when(semanticMemory.findAllCurrent(any(MemoryReadFilter.class))).thenReturn(List.of(
                    testEntity("e1", "张三", EntityType.PERSON),
                    testEntity("e2", "项目A", EntityType.PROJECT)));

            mockMvc.perform(get("/api/memories/entities"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(2)))
                    .andExpect(jsonPath("$.data.total").value(2))
                    .andExpect(jsonPath("$.data.page").value(0));
        }

        @Test
        void 实体列表_按type过滤() throws Exception {
            when(semanticMemory.findAllCurrent(any(MemoryReadFilter.class))).thenReturn(List.of(
                    testEntity("e1", "张三", EntityType.PERSON),
                    testEntity("e2", "项目A", EntityType.PROJECT)));

            mockMvc.perform(get("/api/memories/entities").param("type", "PERSON"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].type").value("PERSON"));
        }

        @Test
        void 实体列表_按记忆元数据过滤() throws Exception {
            when(semanticMemory.findAllCurrent(any(MemoryReadFilter.class))).thenReturn(List.of(
                    testEntity("e1", "林夜", EntityType.PERSON),
                    testEntity("e2", "项目A", EntityType.PROJECT)));
            when(provenanceRepository.loadEntityMetadata(anyCollection()))
                    .thenReturn(Map.of(
                    "e1", new EntityMetadata("e1", "domain:knowledge-base:novel", "DOMAIN_MEMORY", "FICTIONAL"),
                    "e2", new EntityMetadata("e2", "user:default", "USER_FACT", "REAL")));

            mockMvc.perform(get("/api/memories/entities")
                            .param("memoryScope", "DOMAIN_MEMORY")
                            .param("realityType", "FICTIONAL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].id").value("e1"))
                    .andExpect(jsonPath("$.data.items[0].spaceId").value("domain:knowledge-base:novel"))
                    .andExpect(jsonPath("$.data.items[0].memoryScope").value("DOMAIN_MEMORY"))
                    .andExpect(jsonPath("$.data.items[0].realityType").value("FICTIONAL"));
        }

        @Test
        void 实体列表_按来源字段过滤() throws Exception {
            when(semanticMemory.findAllCurrent(any(MemoryReadFilter.class))).thenReturn(List.of(
                    testEntity("e1", "林夜", EntityType.PERSON),
                    testEntity("e2", "项目A", EntityType.PROJECT)));
            when(provenanceRepository.findEntityIdsByProvenanceFilters(
                    eq(null), eq("kb-1"), eq(null)))
                    .thenReturn(Set.of("e1"));

            mockMvc.perform(get("/api/memories/entities")
                            .param("sourceKnowledgeBaseId", "kb-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].id").value("e1"));
        }

        @Test
        void 实体详情_存在() throws Exception {
            stubReadable(testEntity("e1", "张三", EntityType.PERSON));

            mockMvc.perform(get("/api/memories/entities/e1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("e1"))
                    .andExpect(jsonPath("$.data.name").value("张三"));
        }

        @Test
        void 实体详情_不存在_返回404() throws Exception {
            mockMvc.perform(get("/api/memories/entities/not-exist"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 归档实体_成功() throws Exception {
            var entity = testEntity("e1", "张三", EntityType.PERSON);
            stubReadable(entity);

            mockMvc.perform(delete("/api/memories/entities/e1"))
                    .andExpect(status().isOk());

        verify(semanticMemory).archive(entity, ChangeSource.UI_EDIT);
        }

        @Test
        void 归档实体_不存在_返回404() throws Exception {
            mockMvc.perform(delete("/api/memories/entities/not-exist"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 创建实体_标记为手动来源() throws Exception {
            when(semanticMemory.upsertWithConflictDetection(
                    any(TemporalEntity.class), eq("manual-edit"), any(MemoryWriteContext.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            mockMvc.perform(post("/api/memories/entities")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"张三","type":"PERSON","description":"产品经理","importanceScore":0.7}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("张三"));

            ArgumentCaptor<MemoryWriteContext> captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
            verify(semanticMemory).upsertWithConflictDetection(
                    any(TemporalEntity.class), eq("manual-edit"), captor.capture());
            assertThat(captor.getValue().originType()).isEqualTo(MemoryOriginType.MANUAL);
        }

        @Test
        void 更新实体_主账户路径使用主账户写入上下文() throws Exception {
            var entity = testEntity("e-main", "林夜", EntityType.PERSON);
            stubReadable(entity);
            when(semanticMemory.upsertWithConflictDetection(
                    any(TemporalEntity.class), eq("manual-edit"), any(MemoryWriteContext.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            mockMvc.perform(put("/api/memories/entities/e-main")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"description":"更新后描述","importanceScore":0.9}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.description").value("更新后描述"));

            ArgumentCaptor<MemoryWriteContext> captor = ArgumentCaptor.forClass(MemoryWriteContext.class);
            verify(semanticMemory).upsertWithConflictDetection(
                    any(TemporalEntity.class), eq("manual-edit"), captor.capture());
            assertThat(captor.getValue().spaceId()).isNull();
            assertThat(captor.getValue().memoryScope()).isNull();
        }

        @Test
        void 更新隔离项目继承实体_创建overlay且不改base() throws Exception {
            ProjectContextResolver resolver = mock(ProjectContextResolver.class);
            when(resolver.resolve("p-1")).thenReturn(
                    new ProjectContext("p-1", "space-project", "space-personal", "space-experience", true));
            var controller = new MemoryController(
                    semanticMemory, episodicMemory, proceduralMemory,
                    hybridRetriever, consolidationPipeline, null, null, null, forgettingLogRepository,
                    provenanceRepository, resolver, new MemoryAccessPolicy(), null, null);
            var projectMvc = MockMvcBuilders.standaloneSetup(controller).build();
            var base = testEntity("base-1", "咖啡偏好", EntityType.PREFERENCE);
            var overlay = testEntity("overlay-1", "咖啡偏好", EntityType.PREFERENCE);
            when(semanticMemory.findByIds(eq(List.of("base-1")), any(MemoryReadFilter.class)))
                    .thenReturn(Map.of())
                    .thenReturn(Map.of("base-1", base));
            when(semanticMemory.upsertProjectOverlay(
                    any(TemporalEntity.class), eq(base), eq("manual-edit"), any(MemoryWriteContext.class)))
                    .thenReturn(overlay);

            projectMvc.perform(put("/api/memories/entities/base-1")
                            .param("projectId", "p-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"description":"项目里改喝美式"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("overlay-1"));

            ArgumentCaptor<TemporalEntity> overlayCaptor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertProjectOverlay(
                    overlayCaptor.capture(), eq(base), eq("manual-edit"), any(MemoryWriteContext.class));
            assertThat(overlayCaptor.getValue().id()).isNull();
            assertThat(overlayCaptor.getValue().description()).isEqualTo("项目里改喝美式");
            verify(semanticMemory, never()).upsertWithConflictDetection(
                    any(TemporalEntity.class), eq("manual-edit"), any(MemoryWriteContext.class));
        }

        @Test
        void 实体来源明细_返回provenance列表() throws Exception {
            stubReadable(testEntity("e1", "张三", EntityType.PERSON));
            when(provenanceRepository.findEntityProvenances(eq("e1"), eq(null), eq(null), eq(null)))
                    .thenReturn(List.of(new com.lifepilot.interaction.web.model.EntityProvenanceDto(
                            "CHAT",
                            "session-1",
                            "conv-1",
                            "session-1",
                            "turn-1",
                            null,
                            null,
                            null,
                            null,
                            null,
                            "USER_EXPLICIT",
                            "EXPLICIT",
                            0.9f,
                            "张三",
                            0.9f,
                            NOW
                    )));

            mockMvc.perform(get("/api/memories/entities/e1/provenances"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].originType").value("CHAT"))
                    .andExpect(jsonPath("$.data[0].sourceSessionId").value("session-1"));
        }

        @Test
        void 实体来源明细_返回友好名称() throws Exception {
            stubReadable(testEntity("e1", "林夜", EntityType.PERSON));
            when(provenanceRepository.findEntityProvenances(eq("e1"), eq(null), eq(null), eq(null)))
                    .thenReturn(List.of(new com.lifepilot.interaction.web.model.EntityProvenanceDto(
                            "KNOWLEDGE_BASE_DOCUMENT",
                            "doc-1",
                            null,
                            null,
                            null,
                            null,
                            "doc-1",
                            null,
                            "kb-1",
                            null,
                            "DOCUMENT_GROUNDED",
                            "VERIFIED",
                            0.93f,
                            "人物设定",
                            0.93f,
                            NOW
                    )));
            when(provenanceRepository.loadKnowledgeBaseNames(anyCollection()))
                    .thenReturn(Map.of("kb-1", "世界观资料库"));
            when(provenanceRepository.loadDocumentNames(anyCollection()))
                    .thenReturn(Map.of("doc-1", "人物设定.md"));

            mockMvc.perform(get("/api/memories/entities/e1/provenances"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].originType").value("KNOWLEDGE_BASE_DOCUMENT"))
                    .andExpect(jsonPath("$.data[0].sourceKnowledgeBaseName").value("世界观资料库"))
                    .andExpect(jsonPath("$.data[0].sourceDocumentName").value("人物设定.md"));
        }

        @Test
        void 实体来源明细_按来源字段过滤() throws Exception {
            stubReadable(testEntity("e1", "张三", EntityType.PERSON));
            when(provenanceRepository.findEntityProvenances(
                    eq("e1"), eq("KNOWLEDGE_BASE_DOCUMENT"), eq("kb-1"), eq("doc-1")))
                    .thenReturn(List.of(new com.lifepilot.interaction.web.model.EntityProvenanceDto(
                            "KNOWLEDGE_BASE_DOCUMENT",
                            "人物设定集",
                            null,
                            null,
                            null,
                            null,
                            "doc-1",
                            null,
                            "kb-1",
                            null,
                            "DOCUMENT_GROUNDED",
                            "VERIFIED",
                            0.95f,
                            "人物设定集",
                            0.95f,
                            NOW
                    )));
            when(provenanceRepository.loadKnowledgeBaseNames(anyCollection()))
                    .thenReturn(Map.of());
            when(provenanceRepository.loadDocumentNames(anyCollection()))
                    .thenReturn(Map.of());

            mockMvc.perform(get("/api/memories/entities/e1/provenances")
                            .param("originType", "KNOWLEDGE_BASE_DOCUMENT")
                            .param("sourceKnowledgeBaseId", "kb-1")
                            .param("sourceDocumentId", "doc-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].originType").value("KNOWLEDGE_BASE_DOCUMENT"))
                    .andExpect(jsonPath("$.data[0].sourceKnowledgeBaseId").value("kb-1"))
                    .andExpect(jsonPath("$.data[0].sourceDocumentId").value("doc-1"));
        }

        @Test
        void 最近来源摘要_返回知识库和文档友好名称() throws Exception {
            when(provenanceRepository.findRecentProvenanceSummaries(
                    eq(null), eq(null), eq(null), eq(5)))
                    .thenReturn(List.of(new MemoryProvenanceSummaryDto(
                            "e1",
                            "林夜",
                            "PERSON",
                            "人物",
                            "DOMAIN_MEMORY",
                            "FICTIONAL",
                            "KNOWLEDGE_BASE_DOCUMENT",
                            "人物设定手册",
                            null,
                            "session-1",
                            "turn-1",
                            "entry-1",
                            "doc-1",
                            null,
                            "kb-1",
                            null,
                            "DOCUMENT_GROUNDED",
                            "VERIFIED",
                            0.97f,
                            "人物设定手册",
                            0.97f,
                            NOW
                    )));
            when(provenanceRepository.loadKnowledgeBaseNames(anyCollection()))
                    .thenReturn(Map.of("kb-1", "世界观资料库"));
            when(provenanceRepository.loadDocumentNames(anyCollection()))
                    .thenReturn(Map.of("doc-1", "人物设定.md"));

            mockMvc.perform(get("/api/memories/provenances/recent")
                            .param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].entityId").value("e1"))
                    .andExpect(jsonPath("$.data[0].entityName").value("林夜"))
                    .andExpect(jsonPath("$.data[0].entityTypeLabel").value("人物"))
                    .andExpect(jsonPath("$.data[0].entityMemoryScope").value("DOMAIN_MEMORY"))
                    .andExpect(jsonPath("$.data[0].sourceSessionId").value("session-1"))
                    .andExpect(jsonPath("$.data[0].sourceKnowledgeBaseName").value("世界观资料库"))
                    .andExpect(jsonPath("$.data[0].sourceDocumentName").value("人物设定.md"));
        }
    }

    // ── L2 对话 ──────────────────────────────────────────

    @Nested
    class L2对话 {

        @Test
        void 对话列表_默认分页() throws Exception {
            when(episodicMemory.countConversations()).thenReturn(1L);
            when(episodicMemory.listConversations(0, 20)).thenReturn(List.of(testConversation("c1")));

            mockMvc.perform(get("/api/memories/conversations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].id").value("c1"));
        }

        @Test
        void 对话详情_存在() throws Exception {
            when(episodicMemory.getById("c1")).thenReturn(Optional.of(testConversation("c1")));

            mockMvc.perform(get("/api/memories/conversations/c1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("c1"));
        }

        @Test
        void 对话详情_不存在_返回404() throws Exception {
            when(episodicMemory.getById("not-exist")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/memories/conversations/not-exist"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 删除对话_成功() throws Exception {
            when(episodicMemory.delete("c1")).thenReturn(true);

            mockMvc.perform(delete("/api/memories/conversations/c1"))
                    .andExpect(status().isOk());
        }

        @Test
        void 删除对话_不存在_返回404() throws Exception {
            when(episodicMemory.delete("not-exist")).thenReturn(false);

            mockMvc.perform(delete("/api/memories/conversations/not-exist"))
                    .andExpect(status().isNotFound());
        }
    }

    // ── 巩固 ─────────────────────────────────────────────

    @Nested
    class 巩固 {

        @Test
        void 触发巩固_返回200() throws Exception {
            mockMvc.perform(post("/api/memories/consolidate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("accepted"));

            verify(consolidationPipeline, timeout(500)).consolidate(true);
        }
    }

    // ── 关系查询 ─────────────────────────────────────────

    @Nested
    class 关系查询 {

        @Test
        void 关系列表_附带实体名称() throws Exception {
            var relation = testRelation("r1", "e1", "e2");
            when(semanticMemory.findAllCurrentRelations()).thenReturn(List.of(relation));
            when(semanticMemory.findByIds(anyCollection(), any(MemoryReadFilter.class))).thenReturn(Map.of(
                    "e1", testEntity("e1", "张三", EntityType.PERSON),
                    "e2", testEntity("e2", "项目A", EntityType.PROJECT)));
            when(provenanceRepository.loadEntityMetadata(anyCollection()))
                    .thenReturn(Map.of(
                            "e1", new EntityMetadata("e1", "domain:knowledge-base:novel", "DOMAIN_MEMORY", "FICTIONAL"),
                            "e2", new EntityMetadata("e2", "domain:knowledge-base:novel", "DOMAIN_MEMORY", "FICTIONAL")));

            mockMvc.perform(get("/api/memories/relations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].sourceEntityName").value("张三"))
                    .andExpect(jsonPath("$.data.items[0].sourceEntitySpaceId").value("domain:knowledge-base:novel"))
                    .andExpect(jsonPath("$.data.items[0].sourceEntityMemoryScope").value("DOMAIN_MEMORY"))
                    .andExpect(jsonPath("$.data.items[0].sourceEntityRealityType").value("FICTIONAL"))
                    .andExpect(jsonPath("$.data.items[0].targetEntityName").value("项目A"))
                    .andExpect(jsonPath("$.data.items[0].targetEntitySpaceId").value("domain:knowledge-base:novel"))
                    .andExpect(jsonPath("$.data.items[0].targetEntityMemoryScope").value("DOMAIN_MEMORY"))
                    .andExpect(jsonPath("$.data.items[0].targetEntityRealityType").value("FICTIONAL"));
        }
    }
}
