package com.lifepilot.memory.lifecycle;

import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.lifecycle.feedback.RegenerationQueueRepository;
import com.lifepilot.memory.lifecycle.listeners.DerivedEntityListener;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.VersionMerger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link DerivedEntityListener} 集成测试 —— 轻量 Flyway + {@link SingleConnectionDataSource}
 * 真跑 V15/V16 迁移，不启动完整 Spring 容器。验证：
 * <ul>
 *   <li>源 EXPERIENCE 转 {@code SUPERSEDED} 时派生实体（CUSTOM/非画像）进入
 *       {@code REGENERATION_NEEDED} 且入队</li>
 *   <li>画像实体按 {@code derivation_sources} 失效比例 ≥ 20% 阈值触发</li>
 *   <li>{@code source=DERIVATION_TRIGGER} 的入口事件被短路，防递归环</li>
 *   <li>非触发状态事件不产生任何队列变化</li>
 * </ul>
 *
 * <p>测试内部用 JdbcTemplate 手工 INSERT memory_entities / memory_entity_versions /
 * memory_spaces 最小骨架，绕过 @SpringBootTest 启动。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("DerivedEntityListener 集成测试")
class DerivedEntityListener_集成测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final String SPACE_ID = "space-派生级联";
    private static final String PROFILE_NAME = "__consolidated_profile";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private RegenerationQueueRepository queueRepo;
    private DerivedEntityListener listener;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-derived-" + dbId + ".db");
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
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);

        publishedEvents = new ArrayList<>();
        ApplicationEventPublisher publisher = publishedEvents::add;
        semanticMemory.setEventPublisher(publisher);

        queueRepo = new RegenerationQueueRepository(jdbcTemplate);

        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        listener = new DerivedEntityListener(semanticMemory, queueRepo, clock);

        插入记忆空间(SPACE_ID);
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
    void 源EXPERIENCE转SUPERSEDED应使依赖的CONTRASTIVE_INSIGHT进入REGENERATION_NEEDED_且入队() {
        插入ACTIVE实体("exp-A", "EXPERIENCE", "经验A", false, List.of());
        插入ACTIVE实体("exp-B", "EXPERIENCE", "经验B", false, List.of());
        // 用 CUSTOM 类型 + 非画像 name 模拟 CONTRASTIVE_INSIGHT（当前代码库无该枚举）
        插入ACTIVE实体("insight-1", "CUSTOM", "contrastive-insight-ab",
                true, List.of("exp-A", "exp-B"));

        listener.onLifecycleChanged(new EntityLifecycleChanged(
                "exp-A", "EXPERIENCE",
                LifecycleState.ACTIVE, LifecycleState.SUPERSEDED,
                "conflict", ChangeSource.CONFLICT_RESOLVE));

        assertThat(读取LifecycleState("insight-1"))
                .as("派生 insight 应转 REGENERATION_NEEDED")
                .isEqualTo(LifecycleState.REGENERATION_NEEDED);
        assertThat(queueRepo.countPendingByDerived("insight-1"))
                .as("入队 derivation_regeneration_queue 一条")
                .isEqualTo(1);

        // 事件由 SemanticMemory 代发；source=DERIVATION_TRIGGER 的那条事件不再产生新队列项
        var triggeredEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .filter(e -> e.entityId().equals("insight-1"))
                .toList();
        assertThat(triggeredEvents)
                .as("仅代发一次 insight 的 EntityLifecycleChanged")
                .hasSize(1);
        assertThat(triggeredEvents.getFirst().source()).isEqualTo(ChangeSource.DERIVATION_TRIGGER);
        assertThat(triggeredEvents.getFirst().newState()).isEqualTo(LifecycleState.REGENERATION_NEEDED);
    }

    @Test
    void 画像10源失效2个达20阈值应触发重算() {
        List<String> sourceIds = 插入源偏好批次(10);
        插入ACTIVE实体("profile-1", "CUSTOM", PROFILE_NAME, true, sourceIds);

        // 先直接把 2 个源置为 SUPERSEDED（模拟早已失效）
        直接改LifecycleState(sourceIds.get(0), LifecycleState.SUPERSEDED);
        直接改LifecycleState(sourceIds.get(1), LifecycleState.SUPERSEDED);

        // 对刚置失效的其中一个源发 SUPERSEDED 事件触发检查
        listener.onLifecycleChanged(new EntityLifecycleChanged(
                sourceIds.get(1), "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.SUPERSEDED,
                "conflict", ChangeSource.CONFLICT_RESOLVE));

        assertThat(读取LifecycleState("profile-1"))
                .as("2/10=20% 达阈值，画像应进入 REGENERATION_NEEDED")
                .isEqualTo(LifecycleState.REGENERATION_NEEDED);
        assertThat(queueRepo.countPendingByDerived("profile-1")).isEqualTo(1);
    }

    @Test
    void 画像10源失效1个未达20阈值不触发() {
        List<String> sourceIds = 插入源偏好批次(10);
        插入ACTIVE实体("profile-2", "CUSTOM", PROFILE_NAME, true, sourceIds);

        直接改LifecycleState(sourceIds.get(0), LifecycleState.SUPERSEDED);

        listener.onLifecycleChanged(new EntityLifecycleChanged(
                sourceIds.get(0), "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.SUPERSEDED,
                "conflict", ChangeSource.CONFLICT_RESOLVE));

        assertThat(读取LifecycleState("profile-2"))
                .as("1/10=10% 未达 20% 阈值，画像保持 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(queueRepo.countPendingByDerived("profile-2")).isZero();
    }

    @Test
    void 源为物理不存在时计入失效比例() {
        // 画像的 10 个源中只插入 8 条实体 —— 另外 2 个 id 数据库根本查不到，应计入失效
        List<String> sourceIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String id = "src-phantom-" + i;
            插入ACTIVE实体(id, "PREFERENCE", "偏好" + i, false, List.of());
            sourceIds.add(id);
        }
        sourceIds.add("src-phantom-ghost-1");
        sourceIds.add("src-phantom-ghost-2");
        插入ACTIVE实体("profile-3", "CUSTOM", PROFILE_NAME, true, sourceIds);

        // 对第一条存在的源发 SUPERSEDED 事件 —— 本身只是触发检查（findDerivedBySourceEntity
        // 会匹配到 profile-3），关键比例来自两个"物理缺失"的 ghost id（2/10=20%）
        listener.onLifecycleChanged(new EntityLifecycleChanged(
                sourceIds.getFirst(), "PREFERENCE",
                LifecycleState.ACTIVE, LifecycleState.SUPERSEDED,
                "cron", ChangeSource.CRON_EXPIRE));

        assertThat(读取LifecycleState("profile-3"))
                .as("物理缺失的 2 个源计入失效，2/10=20% 达阈值")
                .isEqualTo(LifecycleState.REGENERATION_NEEDED);
        assertThat(queueRepo.countPendingByDerived("profile-3")).isEqualTo(1);
    }

    @Test
    void DERIVATION_TRIGGER源头事件不触发级联_防环() {
        插入ACTIVE实体("exp-C", "EXPERIENCE", "经验C", false, List.of());
        插入ACTIVE实体("insight-x", "CUSTOM", "insight-x",
                true, List.of("exp-C"));

        // 模拟本监听器自身回弹的事件：source=DERIVATION_TRIGGER 必须被短路
        listener.onLifecycleChanged(new EntityLifecycleChanged(
                "exp-C", "EXPERIENCE",
                LifecycleState.ACTIVE, LifecycleState.SUPERSEDED,
                "cascade", ChangeSource.DERIVATION_TRIGGER));

        assertThat(读取LifecycleState("insight-x"))
                .as("DERIVATION_TRIGGER 事件必须被短路，派生实体保持原状态")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(queueRepo.countPendingByDerived("insight-x"))
                .as("防环：入口短路，无任何入队")
                .isZero();
    }

    @Test
    void 非触发状态事件跳过() {
        插入ACTIVE实体("exp-D", "EXPERIENCE", "经验D", false, List.of());
        插入ACTIVE实体("insight-y", "CUSTOM", "insight-y",
                true, List.of("exp-D"));

        // ACTIVE → ARCHIVED / COMPLETED / REGENERATION_NEEDED 均不应级联
        List<LifecycleState> nonTriggerStates = List.of(
                LifecycleState.ARCHIVED,
                LifecycleState.COMPLETED,
                LifecycleState.REGENERATION_NEEDED);
        for (LifecycleState state : nonTriggerStates) {
            listener.onLifecycleChanged(new EntityLifecycleChanged(
                    "exp-D", "EXPERIENCE",
                    LifecycleState.ACTIVE, state,
                    "ignored", ChangeSource.TOOL_EXPLICIT));
        }

        assertThat(读取LifecycleState("insight-y"))
                .as("非 SUPERSEDED/CANCELLED/EXPIRED 事件不级联")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(queueRepo.countPendingByDerived("insight-y")).isZero();
    }

    // ---------- 测试夹具 ----------

    private void 插入记忆空间(String spaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_spaces(id, space_key, space_type, display_name, metadata_json, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, '{}', ?, ?)
                """,
                spaceId, "key-" + spaceId, "测试空间",
                FIXED_NOW.toString(), FIXED_NOW.toString());
    }

    /** 按 id 递增批量插入 10 条源偏好实体。 */
    private List<String> 插入源偏好批次(int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = "src-pref-" + i;
            插入ACTIVE实体(id, "PREFERENCE", "偏好" + i, false, List.of());
            ids.add(id);
        }
        return ids;
    }

    /**
     * 插入一条实体 —— 含 root + version 两行，derivation_sources 按 JSON 数组序列化。
     */
    private void 插入ACTIVE实体(String entityId,
                               String entityType,
                               String name,
                               boolean isDerived,
                               List<String> derivationSources) {
        String sourcesJson = derivationSources.isEmpty()
                ? null
                : "[" + derivationSources.stream()
                    .map(s -> "\"" + s + "\"")
                    .reduce((a, b) -> a + "," + b).orElse("") + "]";
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
                entityId, SPACE_ID, entityType, name, name.toLowerCase(),
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                isDerived ? 1 : 0, sourcesJson);
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

    /** 直接在 DB 改 lifecycle_state，绕过事件系统 —— 用来搭出"早已失效"的前置状态。 */
    private void 直接改LifecycleState(String entityId, LifecycleState state) {
        jdbcTemplate.update(
                "UPDATE memory_entities SET lifecycle_state = ?, updated_at = ? WHERE id = ?",
                state.name(), FIXED_NOW.toString(), entityId);
    }

    private LifecycleState 读取LifecycleState(String entityId) {
        String raw = jdbcTemplate.queryForObject(
                "SELECT lifecycle_state FROM memory_entities WHERE id = ?",
                String.class, entityId);
        return LifecycleState.valueOf(raw);
    }
}
