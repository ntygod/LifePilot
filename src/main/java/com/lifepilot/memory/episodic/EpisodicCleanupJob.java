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
 * <p>按可配置的 Cron 表达式定期执行，查询过期且不含 pinned 消息的对话 ID，
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
     * 定时清理过期 L2 对话记录。
     *
     * <p>清理逻辑：
     * <ol>
     *   <li>用 JdbcTemplate 查询过期对话 ID（排除含 pinned 消息的对话）</li>
     *   <li>逐个调用 {@link EpisodicMemory#delete(String)} 删除</li>
     *   <li>记录 INFO 日志：已清理对话数量和耗时</li>
     *   <li>异常时记录 WARN 日志并终止本次清理</li>
     * </ol>
     */
    @Scheduled(cron = "${lifepilot.memory.episodic-cleanup.cron}")
    public void cleanup() {
        var cleanupConfig = properties.getEpisodicCleanup();
        var startTime = Instant.now();

        try {
            // 计算过期截止时间
            var cutoff = Instant.now().minus(Duration.ofDays(cleanupConfig.getRetentionDays()));

            // 查询过期且非 pinned 对话 ID
            List<String> expiredIds = jdbcTemplate.queryForList(
                    "SELECT c.id FROM conversations c " +
                            "WHERE c.created_at < ? " +
                            "AND c.id NOT IN (" +
                            "    SELECT DISTINCT conversation_id FROM messages WHERE is_pinned = 1" +
                            ") " +
                            "LIMIT ?",
                    String.class,
                    cutoff.toString(),
                    cleanupConfig.getMaxCleanupPerRun());

            if (expiredIds.isEmpty()) {
                log.info("情景记忆清理: 无过期对话需要清理");
                return;
            }

            // 逐个删除
            int deleted = 0;
            for (String conversationId : expiredIds) {
                episodicMemory.delete(conversationId);
                deleted++;
            }

            var elapsed = Duration.between(startTime, Instant.now());
            log.info("情景记忆清理: 已清理 {} 个过期对话, 耗时 {}ms",
                    deleted, elapsed.toMillis());

        } catch (Exception e) {
            var elapsed = Duration.between(startTime, Instant.now());
            log.warn("情景记忆清理: 清理过程异常终止, 耗时 {}ms, error={}",
                    elapsed.toMillis(), e.getMessage(), e);
        }
    }
}
