package com.lifepilot.memory.consolidation;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.ExperiencePromoter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.config.MemoryStoreProperties;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.entity.VersionMerger;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
import com.lifepilot.prompt.PromptRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 经验提升阶段 6 端到端集成测试 — 验证巩固管线阶段 6 的完整数据流。
 *
 * <p>覆盖需求 2：当 L3 存在 {@code importanceScore >= 0.8} 且 {@code accessCount >= 3}
 * 的 EXPERIENCE 实体时，触发巩固阶段 6（{@link ExperiencePromoter#promote()}）应将其提升为
 * L4 {@link com.lifepilot.memory.store.procedural.ProcedureTemplate}，新模板可靠
 * （{@code isReliable(0.7, 2) == true}）且能被 {@link IntentMatcher} 命中。</p>
 *
 * <p>装配策略沿用 {@code SemanticMemory_生命周期字段_集成测试}：文件 SQLite + 真跑 Flyway
 * 迁移（含 V1–V16）+ 手动装配，规避 {@code @SpringBootTest} 被 {@code SkillTestSupport}
 * mock 污染。仅 {@link VectorSearcher} 用 mock 桩替身：冲突检测阶段返回空（避免误判重复），
 * 提升完成后对意图查询返回新模板的向量命中（相似度 1.0），使阶段 6 验证可重复执行且无外部
 * embedding 依赖。</p>
 *
 * @author zsg
 * @since 2026-06-05
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("经验提升阶段6端到端集成测试")
class ExperiencePromotion_集成测试 {

    @Mock
    private VectorSearcher vectorSearcher;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private ProceduralMemory proceduralMemory;
    private ExperiencePromoter experiencePromoter;
    private IntentMatcher intentMatcher;
    private Path dbPath;

    /** 提升出的模板 ID — 在向量桩中按此返回命中，模拟 triggerIntent 向量检索。 */
    private final AtomicReference<String> 提升模板Id = new AtomicReference<>();

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-promote-" + dbId + ".db");
        Files.deleteIfExists(dbPath);
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");

        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new SingleConnectionDataSource(jdbcUrl, true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        // 向量桩：提升前（提升模板 Id 为 null）一律返回空 —— 既满足冲突检测无命中，
        // 也保证写入阶段不误命中。提升后对意图查询返回新模板向量命中（相似度 1.0），
        // 与 IntentMatcher 的 SEMANTIC_WEIGHT=0.7 融合后 0.7 >= matchThreshold(0.6)。
        when(vectorSearcher.searchEntities(any(), anyInt(), anyFloat()))
                .thenAnswer(invocation -> {
                    String templateId = 提升模板Id.get();
                    if (templateId == null) {
                        return List.of();
                    }
                    return List.of(new VectorSearchResult(templateId, 1.0f));
                });

        var conflictDetector = new ConflictDetector(
                jdbcTemplate, vectorSearcher, mock(GenerationRouter.class), 0.92f, mock(PromptRegistry.class));
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher);
        MemoryProjectionService projectionService =
                MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);
        proceduralMemory = new ProceduralMemory(jdbcTemplate, projectionService);
        experiencePromoter = new ExperiencePromoter(semanticMemory, proceduralMemory, new AgentLearningProperties());
        intentMatcher = new IntentMatcher(proceduralMemory, vectorSearcher, jdbcTemplate, new MemoryStoreProperties());
    }

    @AfterEach
    void 清理() throws Exception {
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (dbPath != null) {
            Files.deleteIfExists(dbPath);
        }
    }

    @Test
    @DisplayName("高频经验经阶段6巩固提升为可靠模板且可被意图匹配命中")
    void 满足阈值的经验提升为可靠模板并可被IntentMatcher命中() {
        // ── Arrange：在 L3 构造满足阈值的 EXPERIENCE（importance>=0.8、accessCount>=3）──
        var 源经验 = 构造经验实体("exp-天气-1", "查询某城市未来天气",
                "调用天气工具按城市名查询未来三天天气并汇总要点。", 0.95f);
        var 持久化经验 = semanticMemory.upsertWithConflictDetection(
                源经验, "subtask-reflection", MemoryWriteContext.consolidation("subtask-reflection"));
        // 新建实体 accessCount 固定为 0，递增三次使其达到 minAccessCount(默认 3)
        semanticMemory.incrementAccessCount(持久化经验.id());
        semanticMemory.incrementAccessCount(持久化经验.id());
        semanticMemory.incrementAccessCount(持久化经验.id());

        var 提升前模板数 = proceduralMemory.listAllTemplates().size();
        var 提升前经验 = semanticMemory.findCurrentByType(EntityType.EXPERIENCE).getFirst();
        assertThat(提升前经验.importanceScore()).isGreaterThanOrEqualTo(0.8f);
        assertThat(提升前经验.accessCount()).isGreaterThanOrEqualTo(3);

        // ── Act：触发巩固阶段 6（经验提升）──
        int promoted = experiencePromoter.promote();

        // ── Assert：procedure_templates 增长 ──
        assertThat(promoted).isEqualTo(1);
        var 模板列表 = proceduralMemory.listAllTemplates();
        assertThat(模板列表).hasSize(提升前模板数 + 1);

        var 新模板 = 模板列表.getFirst();
        // 新模板可靠：successRate=1.0 >= 0.7，useCount=accessCount(3) >= 2
        assertThat(新模板.isReliable(0.7f, 2)).isTrue();
        assertThat(新模板.useCount()).isEqualTo(3);
        assertThat(新模板.successRate()).isEqualTo(1.0f);
        // templateId 独立、sourceEntityId 指向源经验，避免向量串号
        assertThat(新模板.templateId()).isNotEqualTo(持久化经验.id());
        assertThat(新模板.sourceEntityId()).isEqualTo(持久化经验.id());

        // ── Assert：IntentMatcher 可命中该模板 ──
        提升模板Id.set(新模板.templateId());
        var match = intentMatcher.match("帮我查一下某城市未来天气");
        assertThat(match).isPresent();
        assertThat(match.get().template().templateId()).isEqualTo(新模板.templateId());
        assertThat(match.get().score()).isGreaterThanOrEqualTo(0.6f);
    }

    @Test
    @DisplayName("重复触发阶段6按sourceEntityId去重不重复提升")
    void 重复触发阶段6应幂等仅提升一次() {
        var 源经验 = 构造经验实体("exp-天气-2", "查询某城市未来天气",
                "调用天气工具按城市名查询未来三天天气并汇总要点。", 0.9f);
        var 持久化经验 = semanticMemory.upsertWithConflictDetection(
                源经验, "subtask-reflection", MemoryWriteContext.consolidation("subtask-reflection"));
        semanticMemory.incrementAccessCount(持久化经验.id());
        semanticMemory.incrementAccessCount(持久化经验.id());
        semanticMemory.incrementAccessCount(持久化经验.id());

        int first = experiencePromoter.promote();
        int second = experiencePromoter.promote();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(proceduralMemory.listAllTemplates()).hasSize(1);
    }

    @Test
    @DisplayName("未达访问次数阈值的经验不被提升")
    void 访问次数不足的经验不应被提升() {
        var 源经验 = 构造经验实体("exp-天气-3", "查询某城市未来天气",
                "调用天气工具按城市名查询未来三天天气并汇总要点。", 0.95f);
        var 持久化经验 = semanticMemory.upsertWithConflictDetection(
                源经验, "subtask-reflection", MemoryWriteContext.consolidation("subtask-reflection"));
        // 仅访问 1 次（< minAccessCount 默认 3）
        semanticMemory.incrementAccessCount(持久化经验.id());

        int promoted = experiencePromoter.promote();

        assertThat(promoted).isZero();
        assertThat(proceduralMemory.listAllTemplates()).isEmpty();
    }

    // ========== helpers ==========

    /** 构造一条 EXPERIENCE 实体（基础构造器，accessCount 由 upsert 重置为 0 后另行递增）。 */
    private TemporalEntity 构造经验实体(String id, String name, String description, float importance) {
        var now = Instant.now();
        return new TemporalEntity(
                id,
                EntityType.EXPERIENCE,
                name,
                description,
                Map.of(),
                1,
                true,
                now,
                null,
                "session-promote",
                1.0f,
                importance,
                0,
                null,
                now,
                now);
    }
}
