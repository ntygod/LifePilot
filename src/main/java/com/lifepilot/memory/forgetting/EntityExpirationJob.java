package com.lifepilot.memory.forgetting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * 实体过期归档定时任务 — 将 validTo 已过的实体标记为 is_current=0。
 *
 * <p>通过 {@code @Scheduled} 定时执行，cron 表达式从配置
 * {@code lifepilot.memory.feedback.expiration-cron} 读取（默认每小时）。</p>
 *
 * @author zsg
 * @since 2026-03-13
 */
public class EntityExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(EntityExpirationJob.class);

    private final JdbcTemplate jdbcTemplate;

    public EntityExpirationJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 定时执行过期实体归档。
     *
     * <p>将 valid_to 已过且 is_current=1 的实体标记为 is_current=0。</p>
     */
    @Scheduled(cron = "${lifepilot.memory.feedback.expiration-cron}")
    public void archiveExpiredEntities() {
        try {
            var now = Instant.now().toString();
            int archived = jdbcTemplate.update("""
                    UPDATE memory_entity_versions
                    SET is_current = 0,
                        updated_at = ?
                    WHERE valid_to IS NOT NULL
                      AND valid_to < datetime('now')
                      AND is_current = 1
                    """, now);
            if (archived > 0) {
                jdbcTemplate.update("""
                        UPDATE memory_entities
                        SET status = 'ARCHIVED',
                            updated_at = ?
                        WHERE id IN (
                            SELECT entity_id
                            FROM memory_entity_versions
                            WHERE valid_to IS NOT NULL
                              AND valid_to < datetime('now')
                              AND is_current = 0
                        )
                        """, now);
            }
            if (archived > 0) {
                log.info("过期实体归档: count={}", archived);
            } else {
                log.debug("无过期实体");
            }
        } catch (Exception e) {
            log.error("过期实体归档失败", e);
        }
    }
}
