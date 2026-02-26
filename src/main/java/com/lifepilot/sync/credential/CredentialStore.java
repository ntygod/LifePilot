package com.lifepilot.sync.credential;

import com.lifepilot.sync.config.SyncProperties;
import com.lifepilot.sync.model.SyncException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * 凭证加密存储组件。
 *
 * <p>使用 AES-GCM 对 OAuth Token、API Key 等敏感凭证进行加密存储。
 * 密钥通过 PBKDF2（SHA-256, 65536 iterations, 256-bit）从主密钥材料派生。
 * 每次加密使用 12 字节随机 IV，防止 IV 重用。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);

    // AES-GCM 技术常量
    private static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int AES_KEY_LENGTH_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 65536;
    // 应用级固定盐前缀，与 profileId 组合生成唯一盐
    private static final String SALT_PREFIX = "lifepilot-credential-salt";

    private final JdbcTemplate jdbcTemplate;
    private final SyncProperties properties;
    private final SecureRandom secureRandom;

    public CredentialStore(JdbcTemplate jdbcTemplate, SyncProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.secureRandom = new SecureRandom();
    }

    /**
     * 加密存储凭证。使用 AES-GCM + 随机 IV。
     *
     * <p>如果同一 profileId + credentialType 已存在，则更新（upsert）。</p>
     *
     * @param profileId      同步配置 ID
     * @param credentialType 凭证类型（如 "access_token"、"refresh_token"、"api_key"）
     * @param plaintext      明文凭证值
     */
    public void store(String profileId, String credentialType, String plaintext) {
        try {
            SecretKey key = deriveKey(profileId);
            byte[] iv = generateIv();
            byte[] ciphertext = encrypt(plaintext, key, iv);

            String encryptedBase64 = Base64.getEncoder().encodeToString(ciphertext);
            String ivBase64 = Base64.getEncoder().encodeToString(iv);
            String now = Instant.now().toString();

            // 使用 INSERT ... ON CONFLICT 实现 upsert
            // sync_credentials 表没有 (profile_id, credential_type) 唯一索引，
            // 所以先尝试查找已有记录，再决定 INSERT 或 UPDATE
            var existing = jdbcTemplate.queryForList(
                    "SELECT id FROM sync_credentials WHERE profile_id = ? AND credential_type = ?",
                    profileId, credentialType
            );

            if (existing.isEmpty()) {
                String id = UUID.randomUUID().toString();
                jdbcTemplate.update(
                        """
                        INSERT INTO sync_credentials (id, profile_id, credential_type, encrypted_value, iv, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        id, profileId, credentialType, encryptedBase64, ivBase64, now, now
                );
                log.info("凭证存储成功: profileId={}, type={}", profileId, credentialType);
            } else {
                jdbcTemplate.update(
                        """
                        UPDATE sync_credentials SET encrypted_value = ?, iv = ?, updated_at = ?
                        WHERE profile_id = ? AND credential_type = ?
                        """,
                        encryptedBase64, ivBase64, now, profileId, credentialType
                );
                log.info("凭证更新成功: profileId={}, type={}", profileId, credentialType);
            }
        } catch (SyncException.CredentialException e) {
            throw e;
        } catch (Exception e) {
            throw new SyncException.CredentialException("凭证加密存储失败: profileId=" + profileId, e);
        }
    }

    /**
     * 解密读取凭证。
     *
     * @param profileId      同步配置 ID
     * @param credentialType 凭证类型
     * @return 解密后的明文凭证值，不存在时返回 {@link Optional#empty()}
     */
    public Optional<String> retrieve(String profileId, String credentialType) {
        var rows = jdbcTemplate.queryForList(
                "SELECT encrypted_value, iv FROM sync_credentials WHERE profile_id = ? AND credential_type = ?",
                profileId, credentialType
        );

        if (rows.isEmpty()) {
            log.debug("凭证不存在: profileId={}, type={}", profileId, credentialType);
            return Optional.empty();
        }

        var row = rows.getFirst();
        String encryptedBase64 = (String) row.get("encrypted_value");
        String ivBase64 = (String) row.get("iv");

        try {
            byte[] ciphertext = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            SecretKey key = deriveKey(profileId);
            String plaintext = decrypt(ciphertext, key, iv);
            return Optional.of(plaintext);
        } catch (SyncException.CredentialException e) {
            throw e;
        } catch (Exception e) {
            throw new SyncException.CredentialException("凭证解密失败: profileId=" + profileId, e);
        }
    }

    /**
     * 删除指定 profile 的所有凭证。
     *
     * @param profileId 同步配置 ID
     */
    public void deleteByProfileId(String profileId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM sync_credentials WHERE profile_id = ?",
                profileId
        );
        log.info("凭证删除完成: profileId={}, 删除数量={}", profileId, deleted);
    }

    // ---- 加密/解密核心方法 ----

    /**
     * 使用 AES-GCM 加密明文。
     */
    private byte[] encrypt(String plaintext, SecretKey key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
            return cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new SyncException.CredentialException("AES-GCM 加密失败", e);
        }
    }

    /**
     * 使用 AES-GCM 解密密文。检测 auth tag 失败（密钥错误）。
     */
    private String decrypt(byte[] ciphertext, SecretKey key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            byte[] plainBytes = cipher.doFinal(ciphertext);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            // AES-GCM auth tag 验证失败 → 密钥不正确
            throw new SyncException.CredentialException("AES-GCM 解密失败：密钥不正确或数据已被篡改", e);
        } catch (Exception e) {
            throw new SyncException.CredentialException("AES-GCM 解密失败", e);
        }
    }

    /**
     * 使用 PBKDF2 从主密钥材料派生 AES-256 密钥。
     *
     * <p>盐由固定应用级前缀 + profileId 组合生成，确保不同 profile 使用不同密钥。</p>
     */
    private SecretKey deriveKey(String profileId) {
        try {
            String keyMaterial = properties.getCredentialKeySource();
            // 盐 = 固定前缀 + profileId，确保不同 profile 派生不同密钥
            byte[] salt = (SALT_PREFIX + ":" + profileId).getBytes(StandardCharsets.UTF_8);

            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            PBEKeySpec spec = new PBEKeySpec(
                    keyMaterial.toCharArray(),
                    salt,
                    PBKDF2_ITERATIONS,
                    AES_KEY_LENGTH_BITS
            );
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new SyncException.CredentialException("PBKDF2 密钥派生失败", e);
        }
    }

    /**
     * 生成 12 字节随机 IV。
     */
    private byte[] generateIv() {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        return iv;
    }
}
