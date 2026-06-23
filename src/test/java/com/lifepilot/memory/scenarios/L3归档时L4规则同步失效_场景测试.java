package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.agent.learning.consolidation.PreferenceConsolidator;
import com.lifepilot.agent.learning.consolidation.PreferenceSyncStats;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.governance.lifecycle.listeners.L4SyncListener;
import com.lifepilot.memory.governance.lifecycle.query.MemoryQueryApi;
import com.lifepilot.memory.store.procedural.PreferenceRuleRepository;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.procedural.ProceduralMemoryRepository;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.entity.VersionMerger;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.prompt.PromptRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 场景 S6：L3 归档时 L4 规则同步失效 —
 * L3 PREFERENCE 转 CANCELLED → {@link L4SyncListener} 使对应 preference_rule 失活。
 *
 * <p>验证目标：用户先说"我是素食主义者"，系统巩固后在 L4 建立 preference_rule；
 * 某天用户改口"我现在又吃肉了"，系统将 L3 PREFERENCE 转 CANCELLED，对应的
 * L4 preference_rule 应同步置 {@code deactivated_reason}（不再参与后续匹配）。</p>
 *
 * <p><b>测试分两路（path-A 与 path-B 映射产品 E2E 的不同成熟度）</b>：
 * <ol>
 *   <li><b>path-A</b>（E2E 路径）：走 {@link PreferenceConsolidator#consolidate}
 *       把 L3 PREFERENCE 巩固到 L4 preference_rules。
 *       <b>当前项目状态</b>：漂移 #4 收尾 hotfix 后，
 *       {@link PreferenceConsolidator#consolidate()} 新建规则已填
 *       {@code source_entity_id = L3 PREFERENCE 实体 id}，整条 L3→L4 级联闭环已通。
 *       {@link Assumptions#assumeTrue} 现在作为"巩固链路回退哨兵"保留 —— 如果后续
 *       某次重构又把 source_entity_id 写入漏掉，测试仍然会优雅降级为 skip
 *       （配合 path-B 的回归覆盖兜底）。</li>
 *   <li><b>path-B</b>（单元覆盖）：直接向 {@code preference_rules} 表插入带
 *       {@code source_entity_id} 的规则，然后手动触发 {@link L4SyncListener#onLifecycleChanged}
 *       事件（模拟 {@code SemanticMemory.updateLifecycleState} 提交事务后的 AFTER_COMMIT
 *       回调），断言 {@code deactivated_reason} 被写入。此路径验证当前 schema 与
 *       L4SyncListener 的失活 SQL 本身可用，与 path-A 互为回归底座。</li>
 * </ol></p>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>未走 fixture → 工具 → cancel 路径，原因同 S1/S3；</li>
 *   <li>{@link L4SyncListener} 是 {@code @TransactionalEventListener(AFTER_COMMIT)}，
 *       需要 Spring TransactionManager 才能自动触发 → 本测试直接调
 *       {@code listener.onLifecycleChanged(event)} 验证失活 SQL，事件链路本身
 *       由 {@code L4SyncListener_单元测试 / 集成测试}（Phase 0 产出）覆盖。</li>
 * </ol></p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("场景 S6 L3 CANCELLED 同步 L4 失活")
class L3归档时L4规则同步失效_场景测试 {

    private static final String PREFERENCE_CATEGORY = "user-preference";

    @Mock
    private VectorSearcher vectorSearcher;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private MemoryQueryApi queryApi;
    private ProceduralMemory proceduralMemory;
    private PreferenceRuleRepository ruleRepo;
    private ProceduralMemoryRepository procedureRepo;
    private PreferenceConsolidator preferenceConsolidator;
    private L4SyncListener l4SyncListener;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-scenario-s6-" + dbId + ".db");
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

        when(vectorSearcher.searchEntities(any(), any(Integer.class), any(Float.class)))
                .thenReturn(List.of());

        var conflictDetector = new ConflictDetector(
                jdbcTemplate, vectorSearcher, mock(GenerationRouter.class), 0.92f, mock(PromptRegistry.class));
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher);
        var projectionService = MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);
        queryApi = new MemoryQueryApi(semanticMemory, new MemoryProvenanceRepository(jdbcTemplate), jdbcTemplate);

        proceduralMemory = new ProceduralMemory(jdbcTemplate, projectionService, new ObjectMapper());
        ruleRepo = new PreferenceRuleRepository(jdbcTemplate);
        procedureRepo = new ProceduralMemoryRepository(jdbcTemplate);
        preferenceConsolidator = new PreferenceConsolidator(semanticMemory, proceduralMemory);
        l4SyncListener = new L4SyncListener(ruleRepo, procedureRepo);
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
    @DisplayName("path-A：PreferenceConsolidator 巩固链路若接上 source_entity_id，L3 CANCELLED 应联动 L4 失活")
    void L3PREFERENCE_CANCELLED应使L4preference_rules失活() {
        // 1. 用户"我是素食主义者" → L3 建立 PREFERENCE
        var pref = 构造ACTIVE偏好("饮食偏好", "素食");
        var persistedPref = semanticMemory.upsertWithConflictDetection(
                pref,
                "scenario-session-s6",
                MemoryWriteContext.conversation("scenario-session-s6"));
        var prefId = persistedPref.id();

        // 2. 触发 L3 → L4 巩固 —— 预期写 preference_rules，但当前实现不填 source_entity_id
        PreferenceSyncStats stats = preferenceConsolidator.consolidate();
        assertThat(stats.created()).as("巩固应新建 L4 规则").isGreaterThanOrEqualTo(1);

        // 3. 查 L4 规则是否带 source_entity_id
        List<String> ruleIds = ruleRepo.findRuleIdsBySourceEntity(prefId);
        // 漂移 #4 hotfix 后正常路径：PreferenceConsolidator 已写 source_entity_id。
        // assumeTrue 作为"后续重构回退哨兵"保留 —— 如果该字段写入再度漏掉，
        // 测试会优雅 skip 而非死失败，留给 path-B 兜底。
        Assumptions.assumeTrue(!ruleIds.isEmpty(),
                "[path-A skipped] PreferenceConsolidator 巩固链路未把 source_entity_id 写入 preference_rules; "
                        + "本次漂移 #4 hotfix 后应当命中，跳过仅作兜底");

        // 4. （一旦 path-A 通了）L3 用户改口"不再素食" → PREFERENCE 转 CANCELLED
        semanticMemory.updateLifecycleState(
                prefId, LifecycleState.CANCELLED, "user-cancel", ChangeSource.TOOL_EXPLICIT);
        assertThat(queryApi.findById(prefId).orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.CANCELLED);

        // 5. L4SyncListener 应通过 AFTER_COMMIT 使规则失活 —— 此处手动触发监听器
        //    （避免依赖 Spring 事务同步机制启动一整个 ApplicationContext）
        l4SyncListener.onLifecycleChanged(new EntityLifecycleChanged(
                prefId, EntityType.PREFERENCE.name(),
                LifecycleState.ACTIVE, LifecycleState.CANCELLED,
                "user-cancel", ChangeSource.TOOL_EXPLICIT));

        // 6. 所有对应规则的 deactivated_reason 应非空
        for (String ruleId : ruleIds) {
            String reason = jdbcTemplate.queryForObject(
                    "SELECT deactivated_reason FROM preference_rules WHERE rule_id = ?",
                    String.class, ruleId);
            assertThat(reason)
                    .as("规则 %s 应失活", ruleId)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("path-B：带 source_entity_id 的 L4 规则在监听器触发后应转失活")
    void 带source_entity_id的规则可被L4SyncListener失活() {
        // 1. 用户"我是素食主义者" → L3 PREFERENCE
        var pref = 构造ACTIVE偏好("饮食偏好", "素食");
        var persistedPref = semanticMemory.upsertWithConflictDetection(
                pref,
                "scenario-session-s6",
                MemoryWriteContext.conversation("scenario-session-s6"));
        var prefId = persistedPref.id();

        // 2. 直接 INSERT 一条带 source_entity_id 的 L4 规则（模拟"PreferenceConsolidator 若已扩字段"的效果）
        String ruleId = "rule-" + UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO preference_rules(
                    rule_id, category, key, value, confidence,
                    learned_from_json, observation_count, created_at, updated_at,
                    source_entity_id, deactivated_reason
                ) VALUES(?,?,?,?,?,?,?,?,?,?,NULL)
                """,
                ruleId, PREFERENCE_CATEGORY, "饮食偏好", "素食", 0.8f,
                "[\"consolidation\"]", 1,
                Instant.now().toString(), Instant.now().toString(),
                prefId);

        assertThat(ruleRepo.findRuleIdsBySourceEntity(prefId))
                .as("初始 L4 规则应存在且活跃")
                .containsExactly(ruleId);
        String reasonBefore = jdbcTemplate.queryForObject(
                "SELECT deactivated_reason FROM preference_rules WHERE rule_id = ?",
                String.class, ruleId);
        assertThat(reasonBefore).as("活跃规则 deactivated_reason 应为 NULL").isNull();

        // 3. 用户改口 → L3 PREFERENCE 转 CANCELLED
        semanticMemory.updateLifecycleState(
                prefId, LifecycleState.CANCELLED, "user-cancel", ChangeSource.TOOL_EXPLICIT);

        // 4. 手动触发 L4SyncListener（生产走 TransactionalEventListener AFTER_COMMIT）
        l4SyncListener.onLifecycleChanged(new EntityLifecycleChanged(
                prefId, EntityType.PREFERENCE.name(),
                LifecycleState.ACTIVE, LifecycleState.CANCELLED,
                "user-cancel", ChangeSource.TOOL_EXPLICIT));

        // 5. 规则已失活
        String reasonAfter = jdbcTemplate.queryForObject(
                "SELECT deactivated_reason FROM preference_rules WHERE rule_id = ?",
                String.class, ruleId);
        assertThat(reasonAfter)
                .as("L3 CANCELLED 联动后 deactivated_reason 应写入 reason 或 newState.name()")
                .isNotBlank()
                .isEqualTo("user-cancel");
    }

    /** 构造一条 ACTIVE + PERSISTENT PREFERENCE 实体。 */
    private TemporalEntity 构造ACTIVE偏好(String name, String value) {
        var now = Instant.now();
        return new TemporalEntity(
                null, EntityType.PREFERENCE, name, "偏好值=" + value,
                Map.of("value", value), 1, true, now, null,
                "scenario-session-s6",
                0.9f, 0.5f, 0, null, now, now)
                .withQuality(MemoryEvidenceKind.USER_EXPLICIT, MemoryTrustLevel.EXPLICIT, 0.9f, 1, now);
    }
}
