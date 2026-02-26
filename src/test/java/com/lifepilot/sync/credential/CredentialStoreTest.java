package com.lifepilot.sync.credential;

import com.lifepilot.sync.config.SyncProperties;
import com.lifepilot.sync.model.SyncException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CredentialStore 集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证 AES-GCM 加密存储的正确性。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@SpringBootTest
@ActiveProfiles("test")
class CredentialStoreTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-credential-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-credential-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CredentialStore credentialStore;
    private String testProfileId;

    @BeforeEach
    void setUp() {
        // 使用默认 SyncProperties（credentialKeySource = "system-key"）
        var properties = new SyncProperties();
        credentialStore = new CredentialStore(jdbcTemplate, properties);

        // 清理测试数据
        jdbcTemplate.execute("DELETE FROM sync_credentials");
        jdbcTemplate.execute("DELETE FROM sync_profiles");

        // 创建测试用 sync_profile（外键依赖）
        testProfileId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbcTemplate.update(
                """
                INSERT INTO sync_profiles (id, name, connector_type, connection_params_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                testProfileId, "测试配置", "todoist", "{}", now, now
        );
    }

    @Test
    void store_retrieve_加密解密往返一致() {
        String plaintext = "sk-test-token-abc123";

        credentialStore.store(testProfileId, "access_token", plaintext);
        var result = credentialStore.retrieve(testProfileId, "access_token");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(plaintext);
    }

    @Test
    void retrieve_不存在的凭证_返回empty() {
        var result = credentialStore.retrieve(testProfileId, "non_existent");
        assertThat(result).isEmpty();
    }

    @Test
    void retrieve_不存在的profileId_返回empty() {
        var result = credentialStore.retrieve("non-existent-profile", "access_token");
        assertThat(result).isEmpty();
    }

    @Test
    void store_相同key两次_更新值() {
        credentialStore.store(testProfileId, "access_token", "old-token");
        credentialStore.store(testProfileId, "access_token", "new-token");

        var result = credentialStore.retrieve(testProfileId, "access_token");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("new-token");

        // 确认只有一条记录
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sync_credentials WHERE profile_id = ? AND credential_type = ?",
                Integer.class, testProfileId, "access_token"
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void store_不同credentialType_独立存储() {
        credentialStore.store(testProfileId, "access_token", "access-value");
        credentialStore.store(testProfileId, "refresh_token", "refresh-value");

        assertThat(credentialStore.retrieve(testProfileId, "access_token").get()).isEqualTo("access-value");
        assertThat(credentialStore.retrieve(testProfileId, "refresh_token").get()).isEqualTo("refresh-value");
    }

    @Test
    void deleteByProfileId_删除所有凭证() {
        credentialStore.store(testProfileId, "access_token", "token1");
        credentialStore.store(testProfileId, "refresh_token", "token2");

        credentialStore.deleteByProfileId(testProfileId);

        assertThat(credentialStore.retrieve(testProfileId, "access_token")).isEmpty();
        assertThat(credentialStore.retrieve(testProfileId, "refresh_token")).isEmpty();
    }

    @Test
    void deleteByProfileId_不存在的profile_不抛异常() {
        credentialStore.deleteByProfileId("non-existent-profile");
    }

    @Test
    void 错误密钥_解密失败抛出CredentialException() {
        // 使用密钥 A 加密
        var propsA = new SyncProperties();
        propsA.setCredentialKeySource("key-alpha");
        var storeA = new CredentialStore(jdbcTemplate, propsA);
        storeA.store(testProfileId, "api_key", "secret-value");

        // 使用密钥 B 解密
        var propsB = new SyncProperties();
        propsB.setCredentialKeySource("key-beta");
        var storeB = new CredentialStore(jdbcTemplate, propsB);

        assertThatThrownBy(() -> storeB.retrieve(testProfileId, "api_key"))
                .isInstanceOf(SyncException.CredentialException.class)
                .hasMessageContaining("密钥不正确");
    }

    @Test
    void store_两次相同明文_生成不同IV() {
        credentialStore.store(testProfileId, "token_a", "same-value");

        // 创建第二个 profile 存储相同明文
        String profileId2 = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbcTemplate.update(
                """
                INSERT INTO sync_profiles (id, name, connector_type, connection_params_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                profileId2, "测试配置2", "todoist", "{}", now, now
        );
        credentialStore.store(profileId2, "token_a", "same-value");

        // 查询两条记录的 IV
        var iv1 = jdbcTemplate.queryForObject(
                "SELECT iv FROM sync_credentials WHERE profile_id = ?",
                String.class, testProfileId
        );
        var iv2 = jdbcTemplate.queryForObject(
                "SELECT iv FROM sync_credentials WHERE profile_id = ?",
                String.class, profileId2
        );

        assertThat(iv1).isNotEqualTo(iv2);
    }

    @Test
    void store_retrieve_中文和特殊字符() {
        String plaintext = "包含中文的Token🔑 & special <chars> \"quoted\"";

        credentialStore.store(testProfileId, "api_key", plaintext);
        var result = credentialStore.retrieve(testProfileId, "api_key");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(plaintext);
    }

    @Test
    void store_retrieve_长字符串() {
        // 模拟较长的 OAuth token
        String plaintext = "a".repeat(4096);

        credentialStore.store(testProfileId, "long_token", plaintext);
        var result = credentialStore.retrieve(testProfileId, "long_token");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(plaintext);
    }

    @Test
    void 数据库中存储的是密文_非明文() {
        String plaintext = "my-secret-token";
        credentialStore.store(testProfileId, "access_token", plaintext);

        var encryptedValue = jdbcTemplate.queryForObject(
                "SELECT encrypted_value FROM sync_credentials WHERE profile_id = ? AND credential_type = ?",
                String.class, testProfileId, "access_token"
        );

        // 密文不应等于明文
        assertThat(encryptedValue).isNotEqualTo(plaintext);
        // 密文应是 Base64 编码
        assertThat(encryptedValue).matches("[A-Za-z0-9+/=]+");
    }
}
