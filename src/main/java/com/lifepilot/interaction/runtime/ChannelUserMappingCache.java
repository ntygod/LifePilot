package com.lifepilot.interaction.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道平台用户 ID 与会话 ID 映射缓存（DB 持久化 + 内存缓存）。
 *
 * <p>入站消息自动写入映射，通知/审批发送时查询。
 * 同时记录平台用户 ID（如飞书 ou_xxx）和平台会话 ID（如飞书 oc_xxx），
 * 因为不同平台的 deliver API 需要不同的定位标识。</p>
 *
 * @author zsg
 * @since 2026-04-15
 */
public class ChannelUserMappingCache {

    private static final Logger log = LoggerFactory.getLogger(ChannelUserMappingCache.class);

    /** instanceId → 映射条目。 */
    private record Entry(String platformUserId, @Nullable String platformSessionId) {}

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public ChannelUserMappingCache(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        warmUp();
    }

    /**
     * 记录入站消息的平台用户 ID 和会话 ID。
     *
     * @param instanceId        渠道实例 ID（如 feishu.87828d5c）
     * @param platformUserId    平台用户 ID（如飞书 ou_xxx）
     * @param platformSessionId 平台会话 ID（如飞书 oc_xxx，可选）
     */
    public void observe(String instanceId, String platformUserId, @Nullable String platformSessionId) {
        if (instanceId == null || platformUserId == null || platformUserId.isBlank()) {
            return;
        }
        var existing = cache.get(instanceId);
        if (existing != null && platformUserId.equals(existing.platformUserId())
                && (platformSessionId == null || platformSessionId.equals(existing.platformSessionId()))) {
            return;
        }
        cache.put(instanceId, new Entry(platformUserId, platformSessionId));
        try {
            jdbcTemplate.update("""
                    INSERT INTO channel_user_mappings (instance_id, platform_user_id, platform_session_id, last_seen_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(instance_id) DO UPDATE
                        SET platform_user_id = ?, platform_session_id = ?, last_seen_at = ?
                    """,
                    instanceId, platformUserId, platformSessionId, Instant.now().toString(),
                    platformUserId, platformSessionId, Instant.now().toString());
        } catch (Exception e) {
            log.debug("渠道用户映射持久化失败: instanceId={}, error={}", instanceId, e.getMessage());
        }
    }

    /** 查询平台用户 ID。 */
    @Nullable
    public String resolve(String instanceId) {
        var entry = cache.get(instanceId);
        return entry != null ? entry.platformUserId() : null;
    }

    /** 查询平台会话 ID（飞书 oc_xxx 等）。 */
    @Nullable
    public String resolveSessionId(String instanceId) {
        var entry = cache.get(instanceId);
        return entry != null ? entry.platformSessionId() : null;
    }

    private void warmUp() {
        try {
            jdbcTemplate.query(
                    "SELECT instance_id, platform_user_id, platform_session_id FROM channel_user_mappings",
                    rs -> {
                        cache.put(rs.getString("instance_id"),
                                new Entry(rs.getString("platform_user_id"), rs.getString("platform_session_id")));
                    });
            if (!cache.isEmpty()) {
                log.info("渠道用户映射缓存预热完成: count={}", cache.size());
            }
        } catch (Exception e) {
            log.debug("渠道用户映射缓存预热跳过（表可能尚未创建）: {}", e.getMessage());
        }
    }
}
