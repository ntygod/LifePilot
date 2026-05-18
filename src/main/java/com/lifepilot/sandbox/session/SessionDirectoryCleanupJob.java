package com.lifepilot.sandbox.session;

import com.lifepilot.config.workspace.WorkspaceResolver;
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
     *
     * <p>只清理临时文件（脚本、缓存等），保留已登记为 session_artifacts 的用户产物。
     * 如果清理后目录为空则删除目录本身。</p>
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

                // 仅清理超期或孤儿目录
                if (lastActivity != null && !lastActivity.isBefore(threshold)) {
                    skipped++;
                    continue;
                }

                // 查询该 session 已登记的产物文件路径（这些不能删）
                var preservedPaths = queryArtifactPaths(sessionId);

                try {
                    int filesRemoved = cleanDirectoryPreservingArtifacts(dir, preservedPaths);
                    if (filesRemoved > 0) {
                        cleaned++;
                        log.debug("清理过期会话临时文件: sessionId={}, filesRemoved={}, preserved={}",
                                sessionId, filesRemoved, preservedPaths.size());
                    }
                    // 如果目录为空（所有文件都是临时的已删完），删除目录本身
                    if (isDirectoryEmpty(dir)) {
                        Files.deleteIfExists(dir);
                    }
                } catch (IOException e) {
                    log.warn("清理会话目录失败: sessionId={}, error={}", sessionId, e.getMessage());
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
     * 清理目录中的临时文件，保留已登记为 artifact 的文件。
     *
     * @return 删除的文件数
     */
    private int cleanDirectoryPreservingArtifacts(Path dir, java.util.Set<Path> preservedPaths) throws IOException {
        int removed = 0;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path normalized = file.toAbsolutePath().normalize();
                if (preservedPaths.contains(normalized)) {
                    continue; // 用户产物，保留
                }
                Files.deleteIfExists(file);
                removed++;
            }
        }
        // 清理空子目录（自底向上）
        try (Stream<Path> dirs = Files.walk(dir)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> !d.equals(dir))
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(d -> {
                        try {
                            if (isDirectoryEmpty(d)) Files.deleteIfExists(d);
                        } catch (IOException ignored) {}
                    });
        }
        return removed;
    }

    private boolean isDirectoryEmpty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }

    /**
     * 查询该 session 已登记的产物文件绝对路径集合。
     */
    private java.util.Set<Path> queryArtifactPaths(String sessionId) {
        try {
            var paths = jdbcTemplate.queryForList(
                    "SELECT payload_json FROM session_artifacts WHERE session_id = ? AND status = 'ACTIVE'",
                    String.class,
                    sessionId);
            var result = new java.util.HashSet<Path>();
            for (String json : paths) {
                // payload_json 中 "path" 字段存储绝对路径
                int idx = json.indexOf("\"path\"");
                if (idx < 0) continue;
                int colonIdx = json.indexOf(':', idx);
                int quoteStart = json.indexOf('"', colonIdx + 1);
                int quoteEnd = json.indexOf('"', quoteStart + 1);
                if (quoteStart >= 0 && quoteEnd > quoteStart) {
                    String path = json.substring(quoteStart + 1, quoteEnd)
                            .replace("\\\\", "\\"); // JSON 转义
                    result.add(Path.of(path).toAbsolutePath().normalize());
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("查询 artifact 路径失败: sessionId={}, error={}", sessionId, e.getMessage());
            return java.util.Set.of();
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
