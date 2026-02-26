package com.lifepilot.sync.repository;

import com.lifepilot.sync.model.ConflictPolicy;
import com.lifepilot.sync.model.SyncConflict;
import com.lifepilot.sync.model.SyncConflict.ConflictStatus;
import com.lifepilot.sync.model.SyncDirection;
import com.lifepilot.sync.model.SyncProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SyncConflictRepository 集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证冲突记录的创建、查询和解决操作的正确性。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@SpringBootTest
@ActiveProfiles("test")
class SyncConflictRepositoryTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-sync-conflict-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-sync-conflict-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SyncConflictRepository repository;
    private SyncProfileRepository profileRepository;
    private String testProfileId;

    @BeforeEach
    void setUp() {
        repository = new SyncConflictRepository(jdbcTemplate);
        profileRepository = new SyncProfileRepository(jdbcTemplate);

        // 清理测试数据（按外键依赖顺序）
        jdbcTemplate.execute("DELETE FROM sync_conflicts");
        jdbcTemplate.execute("DELETE FROM sync_state");
        jdbcTemplate.execute("DELETE FROM sync_credentials");
        jdbcTemplate.execute("DELETE FROM sync_records");
        jdbcTemplate.execute("DELETE FROM sync_profiles");

        // 创建测试用 SyncProfile（sync_conflicts 外键依赖）
        testProfileId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        profileRepository.create(SyncProfile.builder()
                .id(testProfileId)
                .name("测试配置")
                .connectorType("todoist")
                .connectionParamsJson("{}")
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .conflictPolicy(ConflictPolicy.LAST_WRITE_WINS)
                .cronExpression("0 */15 * * * *")
                .enabled(true)
                .dataTypeFilterJson("[\"TodoItem\"]")
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Test
    void create_findUnresolvedByProfileId_往返一致() {
        var conflict = createTestConflict("TodoItem", "local-1");
        repository.create(conflict);

        var results = repository.findUnresolvedByProfileId(testProfileId);
        assertThat(results).hasSize(1);

        var found = results.getFirst();
        assertThat(found.id()).isEqualTo(conflict.id());
        assertThat(found.profileId()).isEqualTo(testProfileId);
        assertThat(found.localEntityType()).isEqualTo("TodoItem");
        assertThat(found.localEntityId()).isEqualTo("local-1");
        assertThat(found.localSnapshotJson()).isEqualTo("{\"title\":\"本地版本\"}");
        assertThat(found.remoteSnapshotJson()).isEqualTo("{\"title\":\"远程版本\"}");
        assertThat(found.status()).isEqualTo(ConflictStatus.UNRESOLVED);
        assertThat(found.resolvedAt()).isNull();
        assertThat(found.createdAt()).isNotNull();
    }

    @Test
    void findUnresolvedByProfileId_多条冲突_按创建时间排序() {
        repository.create(createTestConflict("TodoItem", "local-1"));
        repository.create(createTestConflict("ScheduleItem", "local-2"));
        repository.create(createTestConflict("HabitItem", "local-3"));

        var results = repository.findUnresolvedByProfileId(testProfileId);
        assertThat(results).hasSize(3);
    }

    @Test
    void findUnresolvedByProfileId_无冲突_返回空列表() {
        assertThat(repository.findUnresolvedByProfileId(testProfileId)).isEmpty();
    }

    @Test
    void findUnresolvedByProfileId_返回不可变列表() {
        repository.create(createTestConflict("TodoItem", "local-1"));
        var results = repository.findUnresolvedByProfileId(testProfileId);
        assertThat(results).isUnmodifiable();
    }

    @Test
    void findUnresolvedByProfileId_仅返回未解决冲突() {
        var conflict1 = createTestConflict("TodoItem", "local-1");
        var conflict2 = createTestConflict("ScheduleItem", "local-2");
        repository.create(conflict1);
        repository.create(conflict2);

        // 解决第一个冲突
        repository.resolve(conflict1.id());

        var results = repository.findUnresolvedByProfileId(testProfileId);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().localEntityType()).isEqualTo("ScheduleItem");
    }

    @Test
    void resolve_标记冲突为已解决() {
        var conflict = createTestConflict("TodoItem", "local-1");
        repository.create(conflict);

        repository.resolve(conflict.id());

        // 未解决列表应为空
        assertThat(repository.findUnresolvedByProfileId(testProfileId)).isEmpty();

        // 直接查询数据库验证 resolved_at 已设置
        var resolvedAt = jdbcTemplate.queryForObject(
                "SELECT resolved_at FROM sync_conflicts WHERE id = ?",
                String.class, conflict.id());
        assertThat(resolvedAt).isNotNull();

        var status = jdbcTemplate.queryForObject(
                "SELECT status FROM sync_conflicts WHERE id = ?",
                String.class, conflict.id());
        assertThat(status).isEqualTo("RESOLVED");
    }

    @Test
    void resolve_不存在的id_不抛异常() {
        repository.resolve("non-existent");
    }

    // ---- 辅助方法 ----

    private SyncConflict createTestConflict(String entityType, String localId) {
        String now = Instant.now().toString();
        return new SyncConflict(
                UUID.randomUUID().toString(),
                testProfileId,
                entityType,
                localId,
                "{\"title\":\"本地版本\"}",
                "{\"title\":\"远程版本\"}",
                ConflictStatus.UNRESOLVED,
                null,
                now
        );
    }
}
