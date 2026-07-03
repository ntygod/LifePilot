package com.lifepilot.memory.consolidation;

import ch.qos.logback.classic.Level;
import com.lifepilot.memory.store.support.SemanticMemoryTestSupport;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.ExperienceMerger;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.entity.VersionMerger;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
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
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 经验合并阶段 4 端到端集成测试 — 验证巩固管线阶段 4 的完整数据流。
 *
 * <p>覆盖需求 3：当 L3 存在 ≥2 条向量相似度 ≥ {@code similarityThreshold}(默认 0.85)
 * 且 {@code success} 标志相同的 EXPERIENCE 实体时，触发巩固阶段 4
 * （{@link ExperienceMerger#merge()}）应通过 LLM 合并为一条携带 {@code mergedFrom}
 * 的元经验，并将原始两条归档（{@code memory_entities.status = 'ARCHIVED'}）。</p>
 *
 * <p>装配策略沿用 {@code ExperiencePromotion_集成测试}：文件 SQLite + 真跑 Flyway 迁移
 * + 手动装配，规避 {@code @SpringBootTest} 被 mock 污染。仅外部依赖用 mock 桩替身：</p>
 * <ul>
 *   <li>{@link VectorSearcher}：按 topK 区分两类调用——合并器候选检测固定 topK=5，
 *       返回两条经验互为高相似候选（相似度 0.95 ≥ 0.85）；{@link ConflictDetector}
 *       的语义冲突检测固定 topK=10，返回空，避免元经验写入时被误判为重复。</li>
 *   <li>{@link GenerationRouter}：返回固定 JSON 元经验，避免外部 LLM 依赖。</li>
 *   <li>{@link PromptRegistry}：返回占位提示词（LLM 已被 mock，提示词内容无关）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-06-05
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("经验合并阶段4端到端集成测试")
class ExperienceMerge_集成测试 {

    /** 合并器候选检测使用的 topK（见 ExperienceMerger.findCandidatePairs）。 */
    private static final int 候选检测TopK = 5;
    /** ConflictDetector 语义匹配使用的 topK（见 ConflictDetector.detectConflict）。 */
    private static final int 冲突检测TopK = 10;

    private static final String 经验A_ID = "exp-merge-a";
    private static final String 经验B_ID = "exp-merge-b";

    /** LLM 合并返回的固定元经验 JSON（结构对齐 ExperienceRecord）。 */
    private static final String 元经验JSON = """
            {
              "scenario": "通用天气查询与要点汇总",
              "strategy": "按城市名调用天气工具，统一汇总未来数日要点",
              "lessons": ["先确认目标城市", "聚合关键温度与降水信息"],
              "applicableConditions": ["用户询问某城市天气"],
              "toolsUsed": ["weather"],
              "success": true,
              "failureAttribution": null,
              "effectivenessScore": 0.8,
              "injectionCount": 0,
              "positiveOutcomes": 0,
              "negativeOutcomes": 0
            }
            """;

    @Mock
    private VectorSearcher vectorSearcher;
    @Mock
    private GenerationRouter generationRouter;
    @Mock
    private PromptRegistry promptRegistry;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private ExperienceMerger experienceMerger;
    private Path dbPath;

    private ListAppender<ILoggingEvent> 日志收集器;
    private ch.qos.logback.classic.Logger 合并器Logger;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-merge-" + dbId + ".db");
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

        // 向量桩：合并器候选检测（topK=5）返回两条经验互为高相似候选；
        // ConflictDetector 语义冲突检测（topK=10）返回空 —— 避免元经验写入被误判为重复。
        when(vectorSearcher.searchEntities(any(), eq(候选检测TopK), anyFloat()))
                .thenReturn(List.of(
                        new VectorSearchResult(经验A_ID, 0.95f),
                        new VectorSearchResult(经验B_ID, 0.95f)));
        when(vectorSearcher.searchEntities(any(), eq(冲突检测TopK), anyFloat()))
                .thenReturn(List.of());

        // LLM 合并桩：返回固定元经验 JSON。
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(LlmResponse.cached(元经验JSON, "test", "test-model"));

        // 提示词桩：LLM 已被 mock，提示词内容无关紧要。
        when(promptRegistry.render(any(), any())).thenReturn("merge-prompt");

        var conflictDetector = new ConflictDetector(
                jdbcTemplate, vectorSearcher, generationRouter, 0.92f, promptRegistry);
        MemoryProjectionService projectionService =
                MemoryProjectionTestSupport.create(jdbcTemplate, vectorSearcher);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher, SemanticMemoryTestSupport.memorySpaceRepository(jdbcTemplate), projectionService);

        experienceMerger = new ExperienceMerger(
                semanticMemory, vectorSearcher, generationRouter, promptRegistry, new AgentLearningProperties());

        // 日志收集器：断言"经验合并: 完成, ... merged=1"。
        合并器Logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ExperienceMerger.class);
        日志收集器 = new ListAppender<>();
        日志收集器.start();
        合并器Logger.addAppender(日志收集器);
    }

    @AfterEach
    void 清理() throws Exception {
        if (合并器Logger != null && 日志收集器 != null) {
            合并器Logger.detachAppender(日志收集器);
        }
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (dbPath != null) {
            Files.deleteIfExists(dbPath);
        }
    }

    @Test
    @DisplayName("两条相似且success相同的经验经阶段4合并为mergedFrom元经验并归档原始")
    void 相似且成功标志相同的经验合并为元经验并归档原始两条() {
        // ── Arrange：构造两条 success=true 的相似 EXPERIENCE 写入 L3 ──
        var 经验A = 构造经验实体(经验A_ID, "查询北京未来天气并汇总要点",
                "调用天气工具按城市名查询未来三天天气并汇总要点。", 0.9f, true);
        var 经验B = 构造经验实体(经验B_ID, "查询上海未来天气生成简报",
                "调用天气工具按城市名查询未来三天天气并生成简报。", 0.85f, true);
        var 持久化A = semanticMemory.upsertWithConflictDetection(
                经验A, "subtask-reflection", MemoryWriteContext.consolidation("subtask-reflection"));
        var 持久化B = semanticMemory.upsertWithConflictDetection(
                经验B, "subtask-reflection", MemoryWriteContext.consolidation("subtask-reflection"));

        assertThat(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).hasSize(2);

        // ── Act：触发巩固阶段 4（经验合并）──
        var stats = experienceMerger.merge();

        // ── Assert：恰好合并 1 对 ──
        assertThat(stats.merged()).isEqualTo(1);
        assertThat(stats.candidatesFound()).isGreaterThanOrEqualTo(1);

        // ── Assert：当前仅剩 1 条 EXPERIENCE，即新生成的元经验 ──
        var 当前经验 = semanticMemory.findCurrentByType(EntityType.EXPERIENCE);
        assertThat(当前经验).hasSize(1);
        var 元经验 = 当前经验.getFirst();
        assertThat(元经验.id()).isNotEqualTo(持久化A.id());
        assertThat(元经验.id()).isNotEqualTo(持久化B.id());
        assertThat(元经验.name()).isEqualTo("通用天气查询与要点汇总");

        // mergedFrom 指向原始两条经验
        @SuppressWarnings("unchecked")
        var mergedFrom = (List<String>) 元经验.properties().get("mergedFrom");
        assertThat(mergedFrom).containsExactlyInAnyOrder(持久化A.id(), 持久化B.id());

        // ── Assert：原始两条已归档 ──
        assertThat(查询状态(持久化A.id())).isEqualTo("ARCHIVED");
        assertThat(查询状态(持久化B.id())).isEqualTo("ARCHIVED");

        // ── Assert：日志输出"经验合并: 完成, ... merged=1" ──
        var 完成日志 = 日志收集器.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(msg -> msg.startsWith("经验合并: 完成"))
                .toList();
        assertThat(完成日志).isNotEmpty();
        assertThat(完成日志.getFirst()).contains("merged=1");
    }

    // ========== helpers ==========

    private String 查询状态(String entityId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM memory_entities WHERE id = ?", String.class, entityId);
    }

    /** 构造一条 EXPERIENCE 实体，properties 含 success 标志。 */
    private TemporalEntity 构造经验实体(String id, String name, String description,
                                   float importance, boolean success) {
        var now = Instant.now();
        return new TemporalEntity(
                id,
                EntityType.EXPERIENCE,
                name,
                description,
                Map.of(
                        "lessons", List.of("先确认目标城市"),
                        "toolsUsed", List.of("weather"),
                        "success", success),
                1,
                true,
                now,
                null,
                "session-merge",
                1.0f,
                importance,
                0,
                null,
                now,
                now,
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
}
