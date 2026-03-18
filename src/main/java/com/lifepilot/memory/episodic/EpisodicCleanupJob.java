package com.lifepilot.memory.episodic;

import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * L2 情景记忆定时清理任务 — 删除过期对话记录，保留 pinned 对话。
 *
 * <p>通过 {@code @Scheduled} 按 Cron 表达式定时执行，查询过期且非 pinned 的对话 ID，
 * 逐个调用 {@link EpisodicMemory#delete(String)} 删除。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class EpisodicCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(EpisodicCleanupJob.class);

    private final EpisodicMemory episodicMemory;
    private final JdbcTemplate jdbcTemplate;
    private final MemoryProperties properties;

    public EpisodicCleanupJob(EpisodicMemory episodicMemory,
                              JdbcTemplate jdbcTemplate,
                              MemoryProperties properties) {
        this.episodicMemory = episodicMemory;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 定时清理过期对话记录。
     */
    @Scheduled(cron = "${lifepilot.memory.episodic-cleanup.cron}")
    public void cleanup() {
        var config = properties.getEpisodicCleanup();
        var cutoff = Instant.now().minus(Duration.ofDays(config.getRetentionDays()));
        int maxPerRun = config.getMaxCleanupPerRun();

        log.info("情景清理: 开始执行, cutoff={}, maxPerRun={}", cutoff, maxPerRun);
        long start = System.currentTimeMillis();

        List<String> expiredIds;
        try {
            expiredIds = jdbcTemplate.queryForList(
                    """
                    SELECT c.id FROM conversations c
                    WHERE c.created_at < ?
                    AND c.id NOT IN (
                        SELECT DISTINCT conversation_id FROM messages WHERE is_pinned = 1
                    )
                    LIMIT ?
                    """,
                    String.class, cutoff.toString(), maxPerRun);
        } catch (Exception e) {
            log.warn("情景清理: 查询过期对话失败, error={}", e.getMessage());
            return;
        }

        int deleted = 0;
        for (var id : expiredIds) {
            try {
                if (episodicMemory.delete(id)) {
                    deleted++;
                }
            } catch (Exception e) {
                log.warn("情景清理: 删除对话失败, id={}, error={}", id, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("情景清理: 执行完成, 候选={}, 已删除={}, 耗时={}ms", expiredIds.size(), deleted, elapsed);
    }
}
