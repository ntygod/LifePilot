package com.lifepilot.sandbox.session;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.sandbox.util.SandboxUtils;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * 会话工作目录物理清理定时任务。
 *
 * <p>sandbox TTL 清理只释放进程资源不删目录（保留用户产物），本 Job 负责按
 * session 最后活跃时间清理过期目录，防止磁盘无限增长。</p>
 *
 * <p>清理策略：</p>
 * <ul>
 *   <li>扫描 {@code workspace/sessions/} 下所有子目录</li>
 *   <li>按目录名（sessionId）查 {@code session_store.last_activity_at}</li>
 *   <li>超过 {@code retentionDays} 天的递归删除</li>
 *   <li>session_store 中不存在的目录（孤儿）也删除</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-05-18
 */
public class SessionDirectoryCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SessionDirectoryCleanupJob.class);

    private final WorkspaceResolver workspaceResolver;
    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;

    public SessionDirectoryCleanupJob(WorkspaceResolver workspaceResolver,
                                      JdbcTemplate jdbcTemplate,
                                      int retentionDays) {
        this.workspaceResolver = workspaceResolver;
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
    }

    /**
     * 每天凌晨 4 点执行清理。
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void cleanup() {
        Path sessionsBase = workspaceResolver.resolve().resolve("sessions");
        if (!Files.isDirectory(sessionsBase)) {
            return;
        }

        Instant threshold = Instant.now().minus(Duration.ofDays(retentionDays));
        int cleaned = 0;
        int skipped = 0;

        try (Stream<Path> dirs = Files.list(sessionsBase)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String sessionId = dir.getFileName().toString();
                Instant lastActivity = queryLastActivity(sessionId);

                // 孤儿目录（session_store 中不存在）或超期目录 → 清理
                if (lastActivity == null || lastActivity.isBefore(threshold)) {
                    try {
                        SandboxUtils.deleteDirectoryRecursively(dir);
                        cleaned++;
                        log.debug("清理过期会话目录: sessionId={}, lastActivity={}",
                                sessionId, lastActivity);
                    } catch (IOException e) {
                        log.warn("清理会话目录失败: sessionId={}, error={}", sessionId, e.getMessage());
                    }
                } else {
                    skipped++;
                }
            }
        } catch (IOException e) {
            log.warn("扫描 sessions 目录失败: path={}, error={}", sessionsBase, e.getMessage());
        }

        if (cleaned > 0) {
            log.info("会话目录清理完成: cleaned={}, skipped={}, retentionDays={}",
                    cleaned, skipped, retentionDays);
        }
    }

    /**
     * 查询 session 最后活跃时间；不存在时返回 null（孤儿目录）。
     */
    @Nullable
    private Instant queryLastActivity(String sessionId) {
        try {
            String result = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(last_activity_at, updated_at, created_at) FROM session_store WHERE session_id = ?",
                    String.class,
                    sessionId);
            return result != null ? Instant.parse(result) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
