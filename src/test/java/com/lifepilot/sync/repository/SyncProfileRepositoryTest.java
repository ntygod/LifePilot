package com.lifepilot.sync.repository;

import com.lifepilot.sync.model.ConflictPolicy;
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
 * SyncProfileRepository 集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证 CRUD 操作的正确性。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@SpringBootTest
@ActiveProfiles("test")
class SyncProfileRepositoryTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-sync-profile-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-sync-profile-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SyncProfileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SyncProfileRepository(jdbcTemplate);
        // 清理测试数据（按外键依赖顺序）
        jdbcTemplate.execute("DELETE FROM sync_conflicts");
        jdbcTemplate.execute("DELETE FROM sync_state");
        jdbcTemplate.execute("DELETE FROM sync_credentials");
        jdbcTemplate.execute("DELETE FROM sync_records");
        jdbcTemplate.execute("DELETE FROM sync_profiles");
    }

    @Test
    void create_findById_往返一致() {
        var profile = createTestProfile("todoist", true);

        repository.create(profile);
        var found = repository.findById(profile.id());

        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.id()).isEqualTo(profile.id());
        assertThat(result.name()).isEqualTo(profile.name());
        assertThat(result.connectorType()).isEqualTo("todoist");
        assertThat(result.connectionParamsJson()).isEqualTo(profile.connectionParamsJson());
        assertThat(result.syncDirection()).isEqualTo(SyncDirection.BIDIRECTIONAL);
        assertThat(result.conflictPolicy()).isEqualTo(ConflictPolicy.LAST_WRITE_WINS);
        assertThat(result.cronExpression()).isEqualTo("0 */15 * * * *");
        assertThat(result.enabled()).isTrue();
        assertThat(result.dataTypeFilterJson()).isEqualTo("[\"TodoItem\"]");
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.updatedAt()).isNotNull();
    }

    @Test
    void findById_不存在的id_返回empty() {
        assertThat(repository.findById("non-existent")).isEmpty();
    }

    @Test
    void findAll_返回所有配置() {
        repository.create(createTestProfile("todoist", true));
        repository.create(createTestProfile("caldav", false));
        repository.create(createTestProfile("obsidian", true));

        var all = repository.findAll();
        assertThat(all).hasSize(3);
    }

    @Test
    void findAll_空表_返回空列表() {
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findAllEnabled_仅返回启用的配置() {
        repository.create(createTestProfile("todoist", true));
        repository.create(createTestProfile("caldav", false));
        repository.create(createTestProfile("obsidian", true));

        var enabled = repository.findAllEnabled();
        assertThat(enabled).hasSize(2);
        assertThat(enabled).allMatch(SyncProfile::enabled);
    }

    @Test
    void findAllEnabled_无启用配置_返回空列表() {
        repository.create(createTestProfile("todoist", false));
        assertThat(repository.findAllEnabled()).isEmpty();
    }

    @Test
    void update_修改字段() {
        var profile = createTestProfile("todoist", true);
        repository.create(profile);

        var updated = profile.toBuilder()
                .name("更新后的名称")
                .syncDirection(SyncDirection.PULL_ONLY)
                .conflictPolicy(ConflictPolicy.REMOTE_WINS)
                .enabled(false)
                .build();
        repository.update(updated);

        var found = repository.findById(profile.id());
        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.name()).isEqualTo("更新后的名称");
        assertThat(result.syncDirection()).isEqualTo(SyncDirection.PULL_ONLY);
        assertThat(result.conflictPolicy()).isEqualTo(ConflictPolicy.REMOTE_WINS);
        assertThat(result.enabled()).isFalse();
        // updatedAt 应该被更新
        assertThat(result.updatedAt()).isNotEqualTo(profile.updatedAt());
    }

    @Test
    void delete_删除已有配置() {
        var profile = createTestProfile("todoist", true);
        repository.create(profile);
        assertThat(repository.findById(profile.id())).isPresent();

        repository.delete(profile.id());
        assertThat(repository.findById(profile.id())).isEmpty();
    }

    @Test
    void delete_不存在的id_不抛异常() {
        repository.delete("non-existent");
    }

    @Test
    void findAll_返回不可变列表() {
        repository.create(createTestProfile("todoist", true));
        var all = repository.findAll();
        assertThat(all).isUnmodifiable();
    }

    @Test
    void findAllEnabled_返回不可变列表() {
        repository.create(createTestProfile("todoist", true));
        var enabled = repository.findAllEnabled();
        assertThat(enabled).isUnmodifiable();
    }

    // ---- 辅助方法 ----

    private SyncProfile createTestProfile(String connectorType, boolean enabled) {
        String now = Instant.now().toString();
        return SyncProfile.builder()
                .id(UUID.randomUUID().toString())
                .name("测试-" + connectorType)
                .connectorType(connectorType)
                .connectionParamsJson("{\"url\":\"https://example.com\"}")
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .conflictPolicy(ConflictPolicy.LAST_WRITE_WINS)
                .cronExpression("0 */15 * * * *")
                .enabled(enabled)
                .dataTypeFilterJson("[\"TodoItem\"]")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
