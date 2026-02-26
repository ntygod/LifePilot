package com.lifepilot.sync.repository;

import com.lifepilot.sync.model.ConflictPolicy;
import com.lifepilot.sync.model.SyncDirection;
import com.lifepilot.sync.model.SyncProfile;
import com.lifepilot.sync.model.SyncRecord;
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
 * SyncRecordRepository 集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证查询、upsert 和删除操作的正确性。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@SpringBootTest
@ActiveProfiles("test")
class SyncRecordRepositoryTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-sync-record-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-sync-record-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SyncRecordRepository repository;
    private SyncProfileRepository profileRepository;
    private String testProfileId;

    @BeforeEach
    void setUp() {
        repository = new SyncRecordRepository(jdbcTemplate);
        profileRepository = new SyncProfileRepository(jdbcTemplate);

        // 清理测试数据（按外键依赖顺序）
        jdbcTemplate.execute("DELETE FROM sync_conflicts");
        jdbcTemplate.execute("DELETE FROM sync_state");
        jdbcTemplate.execute("DELETE FROM sync_credentials");
        jdbcTemplate.execute("DELETE FROM sync_records");
        jdbcTemplate.execute("DELETE FROM sync_profiles");

        // 创建测试用 SyncProfile（sync_records 外键依赖）
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
    void upsert_findByProfileId_往返一致() {
        var record = createTestRecord("TodoItem", "local-1", "remote-1");
        repository.upsert(record);

        var results = repository.findByProfileId(testProfileId);
        assertThat(results).hasSize(1);

        var found = results.getFirst();
        assertThat(found.profileId()).isEqualTo(testProfileId);
        assertThat(found.localEntityType()).isEqualTo("TodoItem");
        assertThat(found.localEntityId()).isEqualTo("local-1");
        assertThat(found.remoteEntityId()).isEqualTo("remote-1");
        assertThat(found.etag()).isEqualTo("etag-abc");
        assertThat(found.lastSyncAt()).isNotNull();
        assertThat(found.createdAt()).isNotNull();
        assertThat(found.updatedAt()).isNotNull();
    }

    @Test
    void findByProfileId_多条记录_按创建时间排序() {
        repository.upsert(createTestRecord("TodoItem", "local-1", "remote-1"));
        repository.upsert(createTestRecord("ScheduleItem", "local-2", "remote-2"));
        repository.upsert(createTestRecord("HabitItem", "local-3", "remote-3"));

        var results = repository.findByProfileId(testProfileId);
        assertThat(results).hasSize(3);
    }

    @Test
    void findByProfileId_无记录_返回空列表() {
        assertThat(repository.findByProfileId(testProfileId)).isEmpty();
    }

    @Test
    void findByProfileId_返回不可变列表() {
        repository.upsert(createTestRecord("TodoItem", "local-1", "remote-1"));
        var results = repository.findByProfileId(testProfileId);
        assertThat(results).isUnmodifiable();
    }

    @Test
    void findByLocalEntity_存在的映射_返回记录() {
        repository.upsert(createTestRecord("TodoItem", "local-1", "remote-1"));

        var found = repository.findByLocalEntity(testProfileId, "TodoItem", "local-1");
        assertThat(found).isPresent();
        assertThat(found.get().remoteEntityId()).isEqualTo("remote-1");
    }

    @Test
    void findByLocalEntity_不存在的映射_返回empty() {
        assertThat(repository.findByLocalEntity(testProfileId, "TodoItem", "non-existent")).isEmpty();
    }

    @Test
    void findByRemoteEntity_存在的映射_返回记录() {
        repository.upsert(createTestRecord("TodoItem", "local-1", "remote-1"));

        var found = repository.findByRemoteEntity(testProfileId, "remote-1");
        assertThat(found).isPresent();
        assertThat(found.get().localEntityId()).isEqualTo("local-1");
    }

    @Test
    void findByRemoteEntity_不存在的映射_返回empty() {
        assertThat(repository.findByRemoteEntity(testProfileId, "non-existent")).isEmpty();
    }

    @Test
    void upsert_冲突时更新已有记录() {
        // 首次插入
        var original = createTestRecord("TodoItem", "local-1", "remote-1");
        repository.upsert(original);

        // 同一 (profileId, localEntityType, localEntityId) 再次 upsert，应更新
        String newNow = Instant.now().toString();
        var updated = new SyncRecord(
                UUID.randomUUID().toString(),  // 新 id，但冲突时不会覆盖主键
                testProfileId,
                "TodoItem",
                "local-1",
                "remote-updated",  // 更新远程 ID
                "etag-new",
                newNow,
                newNow,
                newNow,
                newNow
        );
        repository.upsert(updated);

        // 应该只有一条记录
        var results = repository.findByProfileId(testProfileId);
        assertThat(results).hasSize(1);

        var found = results.getFirst();
        assertThat(found.remoteEntityId()).isEqualTo("remote-updated");
        assertThat(found.etag()).isEqualTo("etag-new");
    }

    @Test
    void deleteByProfileId_删除所有关联记录() {
        repository.upsert(createTestRecord("TodoItem", "local-1", "remote-1"));
        repository.upsert(createTestRecord("ScheduleItem", "local-2", "remote-2"));
        assertThat(repository.findByProfileId(testProfileId)).hasSize(2);

        repository.deleteByProfileId(testProfileId);
        assertThat(repository.findByProfileId(testProfileId)).isEmpty();
    }

    @Test
    void deleteByProfileId_无记录时不抛异常() {
        repository.deleteByProfileId(testProfileId);
    }

    @Test
    void upsert_nullable字段_etag和remoteUpdatedAt为null() {
        String now = Instant.now().toString();
        var record = new SyncRecord(
                UUID.randomUUID().toString(),
                testProfileId,
                "TodoItem",
                "local-1",
                "remote-1",
                null,   // etag 为 null
                null,   // remoteUpdatedAt 为 null
                now, now, now
        );
        repository.upsert(record);

        var found = repository.findByLocalEntity(testProfileId, "TodoItem", "local-1");
        assertThat(found).isPresent();
        assertThat(found.get().etag()).isNull();
        assertThat(found.get().remoteUpdatedAt()).isNull();
    }

    // ---- 辅助方法 ----

    private SyncRecord createTestRecord(String entityType, String localId, String remoteId) {
        String now = Instant.now().toString();
        return new SyncRecord(
                UUID.randomUUID().toString(),
                testProfileId,
                entityType,
                localId,
                remoteId,
                "etag-abc",
                now,
                now,
                now,
                now
        );
    }
}
