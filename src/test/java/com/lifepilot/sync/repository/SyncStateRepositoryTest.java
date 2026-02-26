package com.lifepilot.sync.repository;

import com.lifepilot.sync.model.ConflictPolicy;
import com.lifepilot.sync.model.SyncDirection;
import com.lifepilot.sync.model.SyncProfile;
import com.lifepilot.sync.model.SyncState;
import com.lifepilot.sync.model.SyncStatus;
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
 * SyncStateRepository 集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证查询和 upsert 操作的正确性。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@SpringBootTest
@ActiveProfiles("test")
class SyncStateRepositoryTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-sync-state-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-sync-state-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SyncStateRepository repository;
    private SyncProfileRepository profileRepository;
    private String testProfileId;

    @BeforeEach
    void setUp() {
        repository = new SyncStateRepository(jdbcTemplate);
        profileRepository = new SyncProfileRepository(jdbcTemplate);

        // 清理测试数据（按外键依赖顺序）
        jdbcTemplate.execute("DELETE FROM sync_conflicts");
        jdbcTemplate.execute("DELETE FROM sync_state");
        jdbcTemplate.execute("DELETE FROM sync_credentials");
        jdbcTemplate.execute("DELETE FROM sync_records");
        jdbcTemplate.execute("DELETE FROM sync_profiles");

        // 创建测试用 SyncProfile（sync_state 外键依赖）
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
        var state = createTestState(SyncStatus.SUCCESS, "token-abc", null);
        repository.upsert(state);

        var found = repository.findByProfileId(testProfileId);
        assertThat(found).isPresent();

        var result = found.get();
        assertThat(result.profileId()).isEqualTo(testProfileId);
        assertThat(result.syncToken()).isEqualTo("token-abc");
        assertThat(result.lastSyncStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(result.lastErrorMessage()).isNull();
        assertThat(result.lastSyncAt()).isNotNull();
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.updatedAt()).isNotNull();
    }

    @Test
    void findByProfileId_不存在的profileId_返回empty() {
        assertThat(repository.findByProfileId("non-existent")).isEmpty();
    }

    @Test
    void upsert_冲突时更新已有记录() {
        // 首次插入 — SUCCESS
        var original = createTestState(SyncStatus.SUCCESS, "token-1", null);
        repository.upsert(original);

        // 同一 profileId 再次 upsert — FAILED
        var updated = createTestState(SyncStatus.FAILED, "token-2", "连接超时");
        repository.upsert(updated);

        var found = repository.findByProfileId(testProfileId);
        assertThat(found).isPresent();

        var result = found.get();
        assertThat(result.syncToken()).isEqualTo("token-2");
        assertThat(result.lastSyncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(result.lastErrorMessage()).isEqualTo("连接超时");
        // createdAt 不应被覆盖（ON CONFLICT 只更新指定字段）
        assertThat(result.createdAt()).isEqualTo(original.createdAt());
    }

    @Test
    void upsert_nullable字段_syncToken和lastSyncAt为null() {
        String now = Instant.now().toString();
        var state = new SyncState(
                UUID.randomUUID().toString(),
                testProfileId,
                null,   // syncToken 为 null（首次同步前）
                null,   // lastSyncAt 为 null
                SyncStatus.SUCCESS,
                null,
                now, now
        );
        repository.upsert(state);

        var found = repository.findByProfileId(testProfileId);
        assertThat(found).isPresent();
        assertThat(found.get().syncToken()).isNull();
        assertThat(found.get().lastSyncAt()).isNull();
        assertThat(found.get().lastErrorMessage()).isNull();
    }

    @Test
    void upsert_PARTIAL状态_正确存储() {
        var state = createTestState(SyncStatus.PARTIAL, "token-partial", "部分任务推送失败");
        repository.upsert(state);

        var found = repository.findByProfileId(testProfileId);
        assertThat(found).isPresent();
        assertThat(found.get().lastSyncStatus()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(found.get().lastErrorMessage()).isEqualTo("部分任务推送失败");
    }

    // ---- 辅助方法 ----

    private SyncState createTestState(SyncStatus status, String syncToken, String errorMessage) {
        String now = Instant.now().toString();
        return new SyncState(
                UUID.randomUUID().toString(),
                testProfileId,
                syncToken,
                now,
                status,
                errorMessage,
                now, now
        );
    }
}
