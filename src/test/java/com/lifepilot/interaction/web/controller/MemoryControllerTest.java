package com.lifepilot.interaction.web.controller;

import com.lifepilot.memory.consolidation.ConsolidationPipeline;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;

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
    @Mock private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private static final Instant NOW = Instant.parse("2026-03-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        var controller = new MemoryController(
                semanticMemory, episodicMemory, proceduralMemory,
                hybridRetriever, consolidationPipeline, jdbcTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(jdbcTemplate.query(
                        contains("FROM temporal_entities"),
                        any(RowMapper.class),
                        any(Object[].class)))
                .thenReturn(List.of());
    }

    // ── 辅助方法 ──────────────────────────────────────────

    private static TemporalEntity testEntity(String id, String name, EntityType type) {
        return new TemporalEntity(
                id, type, name, name + " 描述",
                Map.of(), 1, true, NOW, null, "conv-1",
                0.9f, 0.5f, 3, NOW, NOW, NOW);
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

    // ── 记忆系统未启用 ───────────────────────────────────

    @Nested
    class 记忆系统未启用 {

        private MockMvc disabledMvc;

        @BeforeEach
        void setUp() {
            var controller = new MemoryController(
                    null, null, null, null, null, jdbcTemplate);
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
            when(semanticMemory.findAllCurrent()).thenReturn(List.of(
                    testEntity("e1", "张三", EntityType.PERSON),
                    testEntity("e2", "项目A", EntityType.PROJECT)));
            when(episodicMemory.countConversations()).thenReturn(10L);
            when(proceduralMemory.listAllTemplates()).thenReturn(List.of());
            when(proceduralMemory.listAllPreferences()).thenReturn(List.of());
            when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM forgetting_log"), eq(Long.class)))
                    .thenReturn(2L);
            when(jdbcTemplate.query(eq("SELECT created_at FROM forgetting_log ORDER BY created_at DESC LIMIT 1"),
                    any(org.springframework.jdbc.core.RowMapper.class)))
                    .thenReturn(List.of("2026-03-12T08:00:00Z"));

            mockMvc.perform(get("/api/memories/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationCount").value(10))
                    .andExpect(jsonPath("$.entityCount").value(5))
                    .andExpect(jsonPath("$.relationCount").value(3))
                    .andExpect(jsonPath("$.forgettingLogCount").value(2))
                    .andExpect(jsonPath("$.entityCountByType.PERSON").value(1))
                    .andExpect(jsonPath("$.entityCountByType.PROJECT").value(1));
        }
    }

    // ── 统一搜索 ─────────────────────────────────────────

    @Nested
    class 统一搜索 {

        @Test
        @SuppressWarnings("unchecked")
        void 搜索返回结果() throws Exception {
            var result = new RetrievalResult(
                    "e1", "PERSON", "张三", "描述", 0.85f,
                    new RetrievalResult.ScoreBreakdown(0.5f, 0.3f, 0.3f, 0.2f, 0.2f, 0.1f, 0.05f, 0.05f),
                    "vector+fts", NOW, 0.5f, null);
            when(hybridRetriever.retrieve(eq("张三"), eq(10), any(RetrievalWeights.class), any()))
                    .thenReturn(List.of(result));
            when(jdbcTemplate.query(
                    contains("FROM temporal_entities"),
                    any(RowMapper.class),
                    any(Object[].class)))
                    .thenAnswer(invocation -> {
                        RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArgument(1);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("id")).thenReturn("e1");
                        when(rs.getString("space_id")).thenReturn("user:default");
                        when(rs.getString("memory_scope")).thenReturn("USER_FACT");
                        when(rs.getString("reality_type")).thenReturn("REAL");
                        return List.of(mapper.mapRow(rs, 0));
                    });

            mockMvc.perform(get("/api/memories/search").param("q", "张三"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].entityId").value("e1"))
                    .andExpect(jsonPath("$[0].name").value("张三"))
                    .andExpect(jsonPath("$[0].spaceId").value("user:default"))
                    .andExpect(jsonPath("$[0].memoryScope").value("USER_FACT"))
                    .andExpect(jsonPath("$[0].realityType").value("REAL"));
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
            when(semanticMemory.findAllCurrent()).thenReturn(List.of(
                    testEntity("e1", "张三", EntityType.PERSON),
                    testEntity("e2", "项目A", EntityType.PROJECT)));

            mockMvc.perform(get("/api/memories/entities"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.page").value(0));
        }

        @Test
        void 实体列表_按type过滤() throws Exception {
            when(semanticMemory.findAllCurrent()).thenReturn(List.of(
                    testEntity("e1", "张三", EntityType.PERSON),
                    testEntity("e2", "项目A", EntityType.PROJECT)));

            mockMvc.perform(get("/api/memories/entities").param("type", "PERSON"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].type").value("PERSON"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void 实体列表_按记忆元数据过滤() throws Exception {
            when(semanticMemory.findAllCurrent()).thenReturn(List.of(
                    testEntity("e1", "林夜", EntityType.PERSON),
                    testEntity("e2", "项目A", EntityType.PROJECT)));
            when(jdbcTemplate.query(
                    contains("FROM temporal_entities"),
                    any(RowMapper.class),
                    any(Object[].class)))
                    .thenAnswer(invocation -> {
                        RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArgument(1);
                        ResultSet first = mock(ResultSet.class);
                        when(first.getString("id")).thenReturn("e1");
                        when(first.getString("space_id")).thenReturn("datastore:novel");
                        when(first.getString("memory_scope")).thenReturn("DOMAIN_MEMORY");
                        when(first.getString("reality_type")).thenReturn("FICTIONAL");

                        ResultSet second = mock(ResultSet.class);
                        when(second.getString("id")).thenReturn("e2");
                        when(second.getString("space_id")).thenReturn("user:default");
                        when(second.getString("memory_scope")).thenReturn("USER_FACT");
                        when(second.getString("reality_type")).thenReturn("REAL");
                        return List.of(mapper.mapRow(first, 0), mapper.mapRow(second, 1));
                    });

            mockMvc.perform(get("/api/memories/entities")
                            .param("memoryScope", "DOMAIN_MEMORY")
                            .param("realityType", "FICTIONAL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value("e1"))
                    .andExpect(jsonPath("$.items[0].spaceId").value("datastore:novel"))
                    .andExpect(jsonPath("$.items[0].memoryScope").value("DOMAIN_MEMORY"))
                    .andExpect(jsonPath("$.items[0].realityType").value("FICTIONAL"));
        }

        @Test
        void 实体详情_存在() throws Exception {
            when(semanticMemory.findById("e1")).thenReturn(Optional.of(
                    testEntity("e1", "张三", EntityType.PERSON)));

            mockMvc.perform(get("/api/memories/entities/e1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("e1"))
                    .andExpect(jsonPath("$.name").value("张三"));
        }

        @Test
        void 实体详情_不存在_返回404() throws Exception {
            when(semanticMemory.findById("not-exist")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/memories/entities/not-exist"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 归档实体_成功() throws Exception {
            var entity = testEntity("e1", "张三", EntityType.PERSON);
            when(semanticMemory.findById("e1")).thenReturn(Optional.of(entity));

            mockMvc.perform(delete("/api/memories/entities/e1"))
                    .andExpect(status().isNoContent());

            verify(semanticMemory).archive(entity);
        }

        @Test
        void 归档实体_不存在_返回404() throws Exception {
            when(semanticMemory.findById("not-exist")).thenReturn(Optional.empty());

            mockMvc.perform(delete("/api/memories/entities/not-exist"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void 实体来源明细_返回provenance列表() throws Exception {
            when(semanticMemory.findById("e1")).thenReturn(Optional.of(
                    testEntity("e1", "张三", EntityType.PERSON)));
            when(jdbcTemplate.query(
                    contains("FROM memory_entity_provenances"),
                    any(RowMapper.class),
                    eq("e1")))
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
                            0.9f,
                            NOW
                    )));

            mockMvc.perform(get("/api/memories/entities/e1/provenances"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].originType").value("CHAT"))
                    .andExpect(jsonPath("$[0].sourceSessionId").value("session-1"));
        }

        @Test
        void 实体来源明细_按来源字段过滤() throws Exception {
            when(semanticMemory.findById("e1")).thenReturn(Optional.of(
                    testEntity("e1", "张三", EntityType.PERSON)));
            when(jdbcTemplate.query(
                    contains("origin_type = ?"),
                    any(RowMapper.class),
                    eq("e1"),
                    eq("KNOWLEDGE_BASE"),
                    eq("kb-1"),
                    eq("ds-1"),
                    eq("doc-1")))
                    .thenReturn(List.of(new com.lifepilot.interaction.web.model.EntityProvenanceDto(
                            "KNOWLEDGE_BASE",
                            "人物设定集",
                            null,
                            null,
                            null,
                            null,
                            "doc-1",
                            "kb-1",
                            "ds-1",
                            null,
                            0.95f,
                            NOW
                    )));

            mockMvc.perform(get("/api/memories/entities/e1/provenances")
                            .param("originType", "KNOWLEDGE_BASE")
                            .param("sourceKnowledgeBaseId", "kb-1")
                            .param("sourceDatastoreId", "ds-1")
                            .param("sourceDocumentId", "doc-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].originType").value("KNOWLEDGE_BASE"))
                    .andExpect(jsonPath("$[0].sourceKnowledgeBaseId").value("kb-1"))
                    .andExpect(jsonPath("$[0].sourceDatastoreId").value("ds-1"))
                    .andExpect(jsonPath("$[0].sourceDocumentId").value("doc-1"));
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
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value("c1"));
        }

        @Test
        void 对话详情_存在() throws Exception {
            when(episodicMemory.getById("c1")).thenReturn(Optional.of(testConversation("c1")));

            mockMvc.perform(get("/api/memories/conversations/c1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("c1"));
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
                    .andExpect(status().isNoContent());
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
        void 触发巩固_返回202() throws Exception {
            mockMvc.perform(post("/api/memories/consolidate"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("accepted"));
        }
    }

    // ── 关系查询 ─────────────────────────────────────────

    @Nested
    class 关系查询 {

        @Test
        void 关系列表_附带实体名称() throws Exception {
            var relation = testRelation("r1", "e1", "e2");
            when(semanticMemory.findAllCurrentRelations()).thenReturn(List.of(relation));
            when(semanticMemory.findByIds(any())).thenReturn(Map.of(
                    "e1", testEntity("e1", "张三", EntityType.PERSON),
                    "e2", testEntity("e2", "项目A", EntityType.PROJECT)));

            mockMvc.perform(get("/api/memories/relations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].sourceEntityName").value("张三"))
                    .andExpect(jsonPath("$.items[0].targetEntityName").value("项目A"));
        }
    }
}
