package com.lifepilot.memory.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.controller.AgentController;
import com.lifepilot.interaction.web.controller.MemoryController;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.memory.forgetting.ForgettingLogRepository;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.scope.MemoryOriginType;
import com.lifepilot.memory.scope.MemoryRealityType;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.loader.AgentMarkdownLoader;
import com.lifepilot.multiagent.loader.AgentMarkdownParser;
import com.lifepilot.multiagent.loader.AgentMarkdownSerializer;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.lifepilot.llm.LlmResponse;

/**
 * 记忆模块端到端冒烟测试。
 *
 * <p>覆盖真实 Spring MVC + 真实 MemoryAutoConfiguration + 文件 SQLite：
 * 管理端点、手动写入治理字段、混合搜索、DOMAIN_MEMORY 注入边界、L4 偏好并入热摘要、
 * 以及关系/related 图链路。</p>
 *
 * @author zsg
 * @since 2026-05-06
 */
@SpringBootTest(
        classes = {
                MemorySmokeE2E_端到端冒烟测试.SmokeApplication.class,
                MemorySmokeE2E_端到端冒烟测试.SmokeTestConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "lifepilot.memory.enabled=true",
                "lifepilot.agent.enabled=true",
                "lifepilot.agent.task.enabled=false",
                "lifepilot.agent.proactive.enabled=false",
                "lifepilot.agent.multi-agent.enabled=false",
                "lifepilot.agent.checkpoint.enabled=false",
                "lifepilot.llm.enabled=false",
                "lifepilot.tool.enabled=false",
                "lifepilot.skills.enabled=false",
                "lifepilot.knowledge.enabled=false",
                "lifepilot.gateway.enabled=false",
                "lifepilot.gateway.channels.web.enabled=true",
                "lifepilot.workflow.enabled=false",
                "lifepilot.sandbox.enabled=false",
                "lifepilot.media.enabled=false",
                "lifepilot.marketplace.enabled=false",
                "lifepilot.notification.enabled=false",
                "lifepilot.meta.enabled=false",
                "lifepilot.mcp.enabled=false",
                "lifepilot.a2a.enabled=false",
                "lifepilot.cli.enabled=false",
                "lifepilot.observability.trace.enabled=false",
                "lifepilot.observability.guardrail.enabled=false",
                "lifepilot.observability.evaluation.enabled=false",
                "spring.main.lazy-initialization=true"
        }
)
@ActiveProfiles("memory-smoke-test")
@AutoConfigureMockMvc
class MemorySmokeE2E_端到端冒烟测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void 配置冒烟数据库(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        String dbPath = Path.of(tmpDir, "lifepilot-memory-smoke-" + DB_ID + ".db")
                .toString()
                .replace("\\", "/");
        String vecDbPath = Path.of(tmpDir, "lifepilot-memory-smoke-vec-" + DB_ID + ".db")
                .toString()
                .replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SemanticMemory semanticMemory;
    @Autowired private MemorySpaceRepository memorySpaceRepository;
    @Autowired private ProceduralMemory proceduralMemory;
    @Autowired private AgentRegistry agentRegistry;

    @Test
    void 健康和统计端点可访问() throws Exception {
        mockMvc.perform(get("/api/memories/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalEntities").exists())
                .andExpect(jsonPath("$.data.totalRelations").exists());

        mockMvc.perform(get("/api/memories/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.entityCount").exists())
                .andExpect(jsonPath("$.data.relationCount").exists());
    }

    @Test
    void 手动实体全链路可创建查询更新归档() throws Exception {
        String needle = unique("manual-preference");
        JsonNode created = createEntity(
                needle,
                EntityType.PREFERENCE,
                "用户确认的冒烟偏好 " + needle,
                Map.of("source", "smoke"),
                0.82f);
        String entityId = created.path("data").path("id").asText();

        assertThat(entityId).isNotBlank();
        assertThat(created.at("/data/evidenceKind").asText()).isEqualTo("USER_CONFIRMED");
        assertThat(created.at("/data/trustLevel").asText()).isEqualTo("EXPLICIT");
        assertThat(created.at("/data/trustScore").floatValue()).isGreaterThanOrEqualTo(0.9f);
        assertThat(created.at("/data/lifecycleState").asText()).isEqualTo("ACTIVE");
        assertThat(created.at("/data/memoryScope").asText()).isEqualTo("USER_PROFILE");

        mockMvc.perform(get("/api/memories/entities").param("q", needle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(entityId));
        mockMvc.perform(get("/api/memories/entities/{id}", entityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(entityId));
        mockMvc.perform(get("/api/memories/entities/{id}/history", entityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(entityId));
        mockMvc.perform(get("/api/memories/entities/{id}/provenances", entityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].originType").value("MANUAL"));

        String updatedDescription = "更新后的冒烟偏好描述 " + needle;
        JsonNode updated = updateEntity(entityId, updatedDescription, 0.91f);
        assertThat(updated.at("/data/description").asText()).contains(updatedDescription);
        assertThat(updated.at("/data/version").asInt()).isGreaterThanOrEqualTo(2);

        mockMvc.perform(delete("/api/memories/entities/{id}", entityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/memories/entities/{id}", entityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lifecycleState").value("ARCHIVED"));
    }

    @Test
    void 搜索能召回可消费L3事实() throws Exception {
        String needle = unique("smoke-needle-l3");
        JsonNode created = createEntity(
                "冒烟可召回事实 " + needle,
                EntityType.TOPIC,
                "这是一条应被全文搜索召回的 L3 事实 " + needle,
                Map.of("kind", "search-smoke"),
                0.76f);
        String entityId = created.at("/data/id").asText();

        JsonNode response = performJson(get("/api/memories/search")
                .param("q", needle)
                .param("topK", "5"));

        assertThat(response.at("/code").asInt()).isEqualTo(200);
        assertThat(arrayItems(response.path("data"))).anySatisfy(item -> {
            assertThat(item.path("entityId").asText()).isEqualTo(entityId);
            assertThat(item.path("memoryScope").asText()).isEqualTo("USER_FACT");
            assertThat(item.path("realityType").asText()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    void DOMAIN_MEMORY可管理但不进入默认热摘要注入() throws Exception {
        String domainNeedle = unique("domain-only-needle");
        String userNeedle = unique("hot-user-needle");
        TemporalEntity domainEntity = persistDomainEntity(domainNeedle);
        JsonNode userEntity = createEntity(
                "冒烟用户画像 " + userNeedle,
                EntityType.PREFERENCE,
                "默认热摘要应注入这条用户画像 " + userNeedle,
                Map.of(),
                0.88f);
        String sourceEntityId = userEntity.at("/data/id").asText();
        proceduralMemory.savePreference(new PreferenceRule(
                "rule-" + UUID.randomUUID(),
                "user-preference",
                "response_language_" + userNeedle,
                "中文",
                0.93f,
                "smoke",
                3,
                Instant.now().minusSeconds(60),
                Instant.now(),
                sourceEntityId,
                null));
        registerSmokeAgent();

        mockMvc.perform(get("/api/memories/entities")
                        .param("memoryScope", "DOMAIN_MEMORY")
                        .param("q", domainNeedle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(domainEntity.id()));

        JsonNode preview = performJson(post("/api/agents/{id}/context-preview", "memory-smoke-agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "message", "预览冒烟上下文",
                        "sessionId", "smoke-session-" + UUID.randomUUID()))));
        String contextMessages = preview.at("/data/segments/contextMessages/content").asText();

        assertThat(contextMessages).contains(userNeedle);
        assertThat(contextMessages).contains("response_language_" + userNeedle + " = 中文");
        assertThat(contextMessages).doesNotContain(domainNeedle);
    }

    @Test
    void 关系端点和related链路可达() throws Exception {
        String relationNeedle = unique("relation-smoke");
        String sourceId = createEntity(
                "源实体 " + relationNeedle,
                EntityType.TOPIC,
                "关系源实体",
                Map.of(),
                0.7f).at("/data/id").asText();
        String targetId = createEntity(
                "目标实体 " + relationNeedle,
                EntityType.TOPIC,
                "关系目标实体",
                Map.of(),
                0.7f).at("/data/id").asText();
        String relationId = "rel-" + UUID.randomUUID();
        Instant now = Instant.now();
        semanticMemory.addRelation(new TemporalRelation(
                relationId,
                sourceId,
                targetId,
                "RELATED_TO",
                0.8f,
                null,
                now,
                null,
                "smoke",
                now));

        mockMvc.perform(get("/api/memories/relations")
                        .param("relationType", "RELATED_TO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id=='" + relationId + "')]").exists());

        JsonNode related = performJson(get("/api/memories/entities/{id}/related", targetId)
                .param("maxDepth", "1"));
        assertThat(arrayItems(related.path("data"))).anySatisfy(item ->
                assertThat(item.path("id").asText()).isEqualTo(sourceId));
    }

    private JsonNode createEntity(String name,
                                  EntityType type,
                                  String description,
                                  Map<String, Object> properties,
                                  float importanceScore) throws Exception {
        return performJson(post("/api/memories/entities")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "name", name,
                        "type", type.name(),
                        "description", description,
                        "properties", properties,
                        "importanceScore", importanceScore))));
    }

    private JsonNode updateEntity(String entityId,
                                  String description,
                                  float importanceScore) throws Exception {
        return performJson(put("/api/memories/entities/{id}", entityId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "description", description,
                        "importanceScore", importanceScore))));
    }

    private TemporalEntity persistDomainEntity(String needle) {
        var domainSpace = memorySpaceRepository.ensureKnowledgeBaseDomainSpace("kb-smoke");
        Instant now = Instant.now();
        var context = new MemoryWriteContext(
                domainSpace.id(),
                MemoryScope.DOMAIN_MEMORY,
                MemoryOriginType.KNOWLEDGE_BASE_DOCUMENT,
                MemoryRealityType.FICTIONAL,
                "doc-smoke",
                null,
                null,
                null,
                "chunk-smoke",
                "doc-smoke",
                "kb-smoke");
        return semanticMemory.upsertWithConflictDetection(
                new TemporalEntity(
                        "domain-" + UUID.randomUUID(),
                        EntityType.TOPIC,
                        "领域知识 " + needle,
                        "知识库领域图记忆，不应默认进入热摘要 " + needle,
                        Map.of(),
                        1,
                        true,
                        now,
                        null,
                        null,
                        1.0f,
                        0.9f,
                        0,
                        null,
                        now,
                        now),
                "doc-smoke",
                context);
    }

    private void registerSmokeAgent() {
        agentRegistry.forceRegister(AgentDefinition.builder()
                .id("memory-smoke-agent")
                .name("记忆冒烟 Agent")
                .description("用于记忆上下文预览冒烟测试")
                .systemPrompt("你是记忆冒烟测试 Agent。")
                .allowedTools(List.of())
                .budget(AgentBudget.LIGHTWEIGHT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of("status", "enabled"))
                .build());
    }

    private JsonNode performJson(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private List<JsonNode> arrayItems(JsonNode array) {
        var items = new ArrayList<JsonNode>();
        array.forEach(items::add);
        return items;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties({ToolConfigProperties.class, SkillConfigProperties.class})
    @Import({
            MemoryController.class,
            AgentController.class,
            ForgettingLogRepository.class,
            MemoryProvenanceRepository.class
    })
    @Profile("memory-smoke-test")
    static class SmokeApplication {
    }

    @TestConfiguration
    @Profile("memory-smoke-test")
    static class SmokeTestConfig {

        @Bean
        @Primary
        GenerationRouter smokeGenerationRouter() {
            GenerationRouter router = mock(GenerationRouter.class);
            when(router.resolveMaxContextWindow(anyString(), any(), any())).thenReturn(16000);
            when(router.call(anyString(), anyString(), any(), any(), any(), any(GenerationCapability.class), any()))
                    .thenReturn(new LlmResponse("{}", null, null, List.of(), Map.of(), 0, 0, null, 0, "smoke", "smoke-model", 0, false));
            return router;
        }

        @Bean
        @Primary
        EmbeddingRouter smokeEmbeddingRouter() {
            EmbeddingRouter router = mock(EmbeddingRouter.class);
            when(router.embed(anyString(), any(), any(), any())).thenReturn(new float[1024]);
            return router;
        }

        @Bean
        @Primary
        AgentToolProvider smokeAgentToolProvider() {
            return (state, streamId) -> List.of();
        }

        @Bean
        @Primary
        DynamicToolRegistry smokeDynamicToolRegistry(ApplicationEventPublisher eventPublisher) {
            return new DynamicToolRegistry(eventPublisher);
        }

        @Bean
        @Primary
        AgentRegistry smokeAgentRegistry(ApplicationEventPublisher eventPublisher) {
            return new AgentRegistry(eventPublisher);
        }

        @Bean
        @Primary
        AgentOrchestrator smokeAgentOrchestrator() {
            return mock(AgentOrchestrator.class);
        }

        @Bean
        @Primary
        KnowledgeBaseManager smokeKnowledgeBaseManager() {
            return mock(KnowledgeBaseManager.class);
        }

        @Bean
        @Primary
        MultiAgentProperties smokeMultiAgentProperties() {
            return new MultiAgentProperties();
        }

        @Bean
        @Primary
        AgentMarkdownParser smokeAgentMarkdownParser() {
            return mock(AgentMarkdownParser.class);
        }

        @Bean
        @Primary
        AgentMarkdownSerializer smokeAgentMarkdownSerializer() {
            return mock(AgentMarkdownSerializer.class);
        }

        @Bean
        @Primary
        AgentMarkdownLoader smokeAgentMarkdownLoader() {
            return mock(AgentMarkdownLoader.class);
        }

        @Bean
        @Primary
        com.lifepilot.config.path.ZhiweiPaths smokeZhiweiPaths() {
            var paths = mock(com.lifepilot.config.path.ZhiweiPaths.class);
            var tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "zhiwei-smoke-test");
            org.mockito.Mockito.lenient().when(paths.home()).thenReturn(tmpDir);
            org.mockito.Mockito.lenient().when(paths.home(anyString())).thenAnswer(inv -> tmpDir.resolve(inv.getArgument(0, String.class)));
            org.mockito.Mockito.lenient().when(paths.workspace()).thenReturn(tmpDir.resolve("workspace"));
            return paths;
        }
    }
}
