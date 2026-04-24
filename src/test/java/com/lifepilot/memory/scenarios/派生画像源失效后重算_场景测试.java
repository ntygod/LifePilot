package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lifepilot.memory.consolidation.UserProfileConsolidator;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.lifecycle.feedback.RegenerationQueueRepository;
import com.lifepilot.memory.lifecycle.listeners.DerivedEntityListener;
import com.lifepilot.memory.lifecycle.scanner.DerivationRegenerator;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.VersionMerger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 场景 S12：派生画像源失效后重算（20% 阈值） — 画像实体
 * ({@code name="__consolidated_profile"}) 的源 PREFERENCE 失效率
 * 达到 20% 才触发 {@link LifecycleState#REGENERATION_NEEDED} +
 * {@link UserProfileConsolidator#consolidate()} 重算。
 *
 * <p>验证目标：
 * <ol>
 *   <li>1/10=10% 源失效时，{@link DerivedEntityListener} 对画像走
 *       {@code shouldRegenerateProfile} 的比例阈值分支不触发，画像保持 ACTIVE；</li>
 *   <li>2/10=20% 源失效（达阈值），画像转 REGENERATION_NEEDED 并写
 *       {@code derivation_regeneration_queue} 一条 PENDING；</li>
 *   <li>{@link DerivationRegenerator#processQueueNow()} 处理队列：
 *       对画像调 {@link UserProfileConsolidator#consolidate()}（mock 不重写实体），
 *       收尾逻辑把画像转 SUPERSEDED，{@code lifecycleReason="regenerated"}。</li>
 * </ol>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>不走 @SpringBootTest（同 B16/B17 降级原因）；</li>
 *   <li>{@link DerivedEntityListener} 是 {@code @TransactionalEventListener(AFTER_COMMIT)}，
 *       单测无事务管理器，改由 lambda publisher 同步转发
 *       {@link EntityLifecycleChanged} → {@code onLifecycleChanged}；</li>
 *   <li>{@link UserProfileConsolidator} 用 mock 替身 —— 默认 {@code consolidate()} 不做事，
 *       依靠 regenerator 的 {@code finalizeToSupersededIfStillPending} 收尾；真实
 *       consolidator 会重写画像实体（状态进 ACTIVE），此处不是本场景关注点。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-24
 */
@DisplayName("场景 S12 派生画像 20% 阈值重算")
class 派生画像源失效后重算_场景测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-24T10:00:00Z");
    private static final String SPACE_ID = "space-s12";
    private static final String PROFILE_ID = "profile-1";
    private static final String PROFILE_NAME = "__consolidated_profile";
    private static final int SOURCE_COUNT = 10;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private DerivedEntityListener derivedEntityListener;
    private DerivationRegenerator regenerator;
    private RegenerationQueueRepository queueRepo;
    private UserProfileConsolidator profileConsolidator;
    private List<Object> publishedEvents;
    private List<String> sourceIds;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-scenario-s12-" + dbId + ".db");
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

        var vectorSearcher = mock(VectorSearcher.class);
        var conflictDetector = mock(ConflictDetector.class);
        var versionMerger = mock(VersionMerger.class);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, versionMerger, vectorSearcher);

        queueRepo = new RegenerationQueueRepository(jdbcTemplate);
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        derivedEntityListener = new DerivedEntityListener(semanticMemory, queueRepo, clock);

        profileConsolidator = mock(UserProfileConsolidator.class);
        regenerator = new DerivationRegenerator(queueRepo, semanticMemory, profileConsolidator);

        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher forwardingPublisher = event -> {
            publishedEvents.add(event);
            if (event instanceof EntityLifecycleChanged lifecycleChanged) {
                derivedEntityListener.onLifecycleChanged(lifecycleChanged);
            }
        };
        semanticMemory.setEventPublisher(forwardingPublisher);

        插入记忆空间(SPACE_ID);

        // 10 条 PREFERENCE 源 + 1 条画像，derivation_sources 指向 10 源
        sourceIds = IntStream.range(0, SOURCE_COUNT)
                .mapToObj(i -> "pref-" + i)
                .toList();
        for (var id : sourceIds) {
            插入PREFERENCE(id);
        }
        插入画像(PROFILE_ID, sourceIds);
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
    @DisplayName("10% 源失效不触发；20% 达阈值触发 REGENERATION_NEEDED + regenerator 收尾 SUPERSEDED")
    void 达到20阈值才触发画像重算_regenerator收尾降级() {
        // 1. 初始：画像 ACTIVE，队列空
        assertThat(读取LifecycleState(PROFILE_ID)).isEqualTo(LifecycleState.ACTIVE);
        assertThat(queueRepo.countPendingByDerived(PROFILE_ID)).isZero();

        // 2. 动作 1：一个源失效（1/10 = 10%）—— 未达阈值
        semanticMemory.updateLifecycleState(
                sourceIds.get(0), LifecycleState.SUPERSEDED,
                "first-invalid", ChangeSource.CONFLICT_RESOLVE);

        assertThat(读取LifecycleState(PROFILE_ID))
                .as("1/10 = 10% < 20% 未达阈值，画像保持 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(queueRepo.countPendingByDerived(PROFILE_ID))
                .as("未入队列")
                .isZero();

        // 3. 动作 2：再一个源失效（2/10 = 20% 达阈值）
        semanticMemory.updateLifecycleState(
                sourceIds.get(1), LifecycleState.SUPERSEDED,
                "second-invalid", ChangeSource.CONFLICT_RESOLVE);

        assertThat(读取LifecycleState(PROFILE_ID))
                .as("2/10 = 20% 达阈值，画像转 REGENERATION_NEEDED")
                .isEqualTo(LifecycleState.REGENERATION_NEEDED);
        assertThat(读取LifecycleReason(PROFILE_ID))
                .as("lifecycleReason 携带触发源 ID")
                .startsWith("source-invalidated:")
                .contains(sourceIds.get(1));
        assertThat(queueRepo.countPendingByDerived(PROFILE_ID))
                .as("队列入一条 PENDING")
                .isEqualTo(1);

        // 4. 动作 3：regenerator 处理队列
        regenerator.processQueueNow();

        // 5. 断言：consolidator.consolidate() 被调至少一次
        verify(profileConsolidator, atLeastOnce()).consolidate();

        // 6. 断言：画像 SUPERSEDED（收尾降级，mock 没真正重写实体）+ reason=regenerated
        var after = semanticMemory.findById(PROFILE_ID).orElseThrow();
        assertThat(after.lifecycleState())
                .as("mock consolidator 不重写实体，regenerator 收尾转 SUPERSEDED")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(after.lifecycleReason())
                .as("画像收尾标记 regenerated（与非画像派生的 no-regenerate-api 区分）")
                .isEqualTo("regenerated");

        // 7. 队列 PENDING → DONE
        assertThat(queueRepo.countPendingByDerived(PROFILE_ID)).isZero();
        assertThat(读取队列Status(PROFILE_ID)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("DERIVATION_TRIGGER 源变更不级联，避免递归环")
    void DERIVATION_TRIGGER源不触发画像重算() {
        semanticMemory.updateLifecycleState(
                sourceIds.get(0), LifecycleState.SUPERSEDED,
                "by-listener", ChangeSource.DERIVATION_TRIGGER);

        assertThat(读取LifecycleState(PROFILE_ID))
                .as("DERIVATION_TRIGGER 入口短路，画像不受影响")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(queueRepo.countPendingByDerived(PROFILE_ID)).isZero();
    }

    // ---------- 测试夹具 ----------

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "S12 测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private void 插入PREFERENCE(String entityId) {
        插入实体行(entityId, entityId, "PREFERENCE", /*isDerived*/ 0, /*sourcesJson*/ null);
    }

    /** 插入画像实体：type=CUSTOM，name=__consolidated_profile，is_derived=1。 */
    private void 插入画像(String entityId, List<String> sources) {
        String json = sources.stream()
                .map(s -> "\"" + s + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]")
                .orElse("[]");
        插入实体行(entityId, PROFILE_NAME, "CUSTOM", /*isDerived*/ 1, json);
    }

    private void 插入实体行(String entityId, String name, String entityType,
                              int isDerived, String derivationSourcesJson) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, temporality, is_derived, derivation_sources)
                VALUES (?, ?, 'PRIVATE', ?, ?, ?, 'UNKNOWN', 'ACTIVE', 0,
                        ?, ?, ?, ?, 'ACTIVE', 'PERSISTENT', ?, ?)
                """,
                entityId, SPACE_ID, entityType, name, name,
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                isDerived, derivationSourcesJson);
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description,
                    extraction_confidence, importance_score,
                    is_current, valid_from, created_at, updated_at)
                VALUES (?, ?, 1, ?, 0.9, 0.5, 1, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entityId, "desc-" + entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    private LifecycleState 读取LifecycleState(String entityId) {
        String raw = jdbcTemplate.queryForObject(
                "SELECT lifecycle_state FROM memory_entities WHERE id = ?",
                String.class, entityId);
        return LifecycleState.valueOf(raw);
    }

    private String 读取LifecycleReason(String entityId) {
        return jdbcTemplate.queryForObject(
                "SELECT lifecycle_reason FROM memory_entities WHERE id = ?",
                String.class, entityId);
    }

    private String 读取队列Status(String derivedEntityId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status FROM derivation_regeneration_queue
                 WHERE derived_entity_id = ?
                 ORDER BY created_at ASC LIMIT 1
                """,
                String.class, derivedEntityId);
    }
}
