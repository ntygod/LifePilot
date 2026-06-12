package com.lifepilot.memory.governance.lifecycle.scanner;

import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * {@link ExpirationScanner} 集成测试 —— Flyway 真跑迁移 + 真 SemanticMemory，
 * 验证过期实体转 EXPIRED + 未过期 / 无 expires_at 不受影响 + 单条失败不中断整批。
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("ExpirationScanner 集成测试")
class ExpirationScanner_集成测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final String SPACE_ID = "space-expire";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private ExpirationScanner scanner;
    private List<Object> publishedEvents;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "lifepilot-expire-" + dbId + ".db");
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

        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        scanner = new ExpirationScanner(semanticMemory, clock);

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
    void 过期实体应转EXPIRED_通过SemanticMemory代发事件() {
        Instant past = FIXED_NOW.minusSeconds(3600);
        Instant future = FIXED_NOW.plusSeconds(3600);
        插入实体("e-past", LifecycleState.ACTIVE, past);
        插入实体("e-future", LifecycleState.ACTIVE, future);
        插入实体("e-null", LifecycleState.ACTIVE, null);
        插入实体("e-expired-already", LifecycleState.EXPIRED, past);

        scanner.scanNow();

        assertThat(读取LifecycleState("e-past"))
                .as("已过期 ACTIVE 应转 EXPIRED")
                .isEqualTo(LifecycleState.EXPIRED);
        assertThat(读取LifecycleState("e-future"))
                .as("未来过期 ACTIVE 仍保持 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(读取LifecycleState("e-null"))
                .as("无 expires_at（PERSISTENT）仍保持 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(读取LifecycleState("e-expired-already"))
                .as("已是 EXPIRED 的不重复处理")
                .isEqualTo(LifecycleState.EXPIRED);

        // 恰一次事件，且 source=CRON_EXPIRE
        var lifecycleEvents = publishedEvents.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents).hasSize(1);
        var event = lifecycleEvents.getFirst();
        assertThat(event.entityId()).isEqualTo("e-past");
        assertThat(event.newState()).isEqualTo(LifecycleState.EXPIRED);
        assertThat(event.source()).isEqualTo(ChangeSource.CRON_EXPIRE);
        assertThat(event.reason()).isEqualTo("ttl-reached");
    }

    @Test
    void 无过期实体不抛异常() {
        // 全部 ACTIVE，无 expires_at 过期
        插入实体("ok-1", LifecycleState.ACTIVE, FIXED_NOW.plusSeconds(7200));
        插入实体("ok-2", LifecycleState.ACTIVE, null);

        scanner.scanNow();

        assertThat(publishedEvents)
                .filteredOn(e -> e instanceof EntityLifecycleChanged)
                .as("无过期时不发事件")
                .isEmpty();
        assertThat(读取LifecycleState("ok-1")).isEqualTo(LifecycleState.ACTIVE);
        assertThat(读取LifecycleState("ok-2")).isEqualTo(LifecycleState.ACTIVE);
    }

    @Test
    void 单条失败不中断整批() {
        Instant past = FIXED_NOW.minusSeconds(3600);
        插入实体("e-bad", LifecycleState.ACTIVE, past);
        插入实体("e-good", LifecycleState.ACTIVE, past);

        // 用 spy + stub：e-bad 抛异常，e-good 正常
        SemanticMemory spyMemory = spy(semanticMemory);
        doThrow(new RuntimeException("模拟 DB 异常"))
                .when(spyMemory).updateLifecycleState(
                        org.mockito.ArgumentMatchers.eq("e-bad"),
                        any(LifecycleState.class), anyString(), any(ChangeSource.class));
        // e-good 走真实路径
        doAnswer(invocation -> {
            semanticMemory.updateLifecycleState(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3));
            return null;
        }).when(spyMemory).updateLifecycleState(
                org.mockito.ArgumentMatchers.eq("e-good"),
                any(LifecycleState.class), anyString(), any(ChangeSource.class));

        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        var spyScanner = new ExpirationScanner(spyMemory, clock);

        // 期望：不抛异常
        spyScanner.scanNow();

        // e-good 应该成功转 EXPIRED
        assertThat(读取LifecycleState("e-good"))
                .as("失败的 e-bad 不阻塞 e-good")
                .isEqualTo(LifecycleState.EXPIRED);
        // e-bad 保持 ACTIVE（updateLifecycleState 抛异常未落库）
        assertThat(读取LifecycleState("e-bad"))
                .as("失败的 e-bad 仍保持 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
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

    private void 插入实体(String entityId, LifecycleState state, Instant expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count,
                    first_seen_at, last_seen_at, created_at, updated_at,
                    lifecycle_state, expires_at, temporality, is_derived)
                VALUES (?, ?, 'PRIVATE', 'GOAL', ?, ?, 'UNKNOWN', 'ACTIVE', 0,
                        ?, ?, ?, ?, ?, ?, 'TEMPORARY', 0)
                """,
                entityId, SPACE_ID, entityId, entityId,
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                FIXED_NOW.toString(), FIXED_NOW.toString(),
                state.name(),
                expiresAt == null ? null : expiresAt.toString());
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
}
