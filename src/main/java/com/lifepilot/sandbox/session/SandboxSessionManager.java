package com.lifepilot.sandbox.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.sandbox.booter.DockerBooter;
import com.lifepilot.sandbox.booter.ProcessBooter;
import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.util.SandboxUtils;

/**
 * 会话级沙箱实例管理器。
 *
 * <p>维护会话 ID 到沙箱实例的映射，支持 TTL 自动续期和过期清理。
 * 使用 {@link ConcurrentHashMap} 保证线程安全，
 * {@link AtomicInteger} 精确追踪活跃会话数，
 * {@link ScheduledExecutorService} 定时扫描过期会话。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class SandboxSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxSessionManager.class);

    private final SandboxConfigProperties config;
    private final SandboxBooter booterTemplate;
    private final WorkspaceResolver workspaceResolver;
    private final ConcurrentHashMap<String, SandboxEntry> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler;

    /**
     * 创建会话管理器并启动定时清理任务。
     *
     * @param config            沙箱配置
     * @param booterTemplate    沙箱启动器模板，用于确定新会话的 booter 类型
     * @param sharedScheduler   共享调度器
     * @param workspaceResolver 工作目录解析器
     */
    public SandboxSessionManager(SandboxConfigProperties config, SandboxBooter booterTemplate,
                                 SharedScheduler sharedScheduler, WorkspaceResolver workspaceResolver) {
        this.config = config;
        this.booterTemplate = booterTemplate;
        this.workspaceResolver = workspaceResolver;
        this.scheduler = sharedScheduler.cleanup();

        int intervalSeconds = config.getSession().getCleanupIntervalSeconds();
        scheduler.scheduleAtFixedRate(this::cleanupExpired, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("SandboxSessionManager 已启动: maxActiveSessions={}, ttlSeconds={}, cleanupIntervalSeconds={}",
                config.getSession().getMaxActiveSessions(),
                config.getSession().getTtlSeconds(),
                intervalSeconds);
    }

    /**
     * 获取或创建会话沙箱实例。
     *
     * <p>如果会话已存在，更新 lastAccessTime（TTL 续期）并返回已有 booter。
     * 如果会话不存在，检查最大活跃会话数限制后创建新的 booter 实例。</p>
     *
     * <p>使用 {@link AtomicInteger} 精确追踪活跃数，避免 ConcurrentHashMap.size() 的并发不精确问题。</p>
     *
     * @param sessionId 会话 ID
     * @return 沙箱启动器实例
     * @throws IllegalStateException 活跃会话数达到上限
     */
    public SandboxBooter getOrCreate(String sessionId) {
        // 先尝试快速路径：会话已存在，仅更新访问时间
        SandboxEntry existing = sessions.computeIfPresent(sessionId, (id, entry) ->
                new SandboxEntry(entry.booter(), Instant.now(), entry.workingDirectory()));
        if (existing != null) {
            log.debug("复用已有会话: sessionId={}", sessionId);
            return existing.booter();
        }

        // 会话不存在，需要创建新会话
        int maxActive = config.getSession().getMaxActiveSessions();

        // 先用 AtomicInteger 做精确的上限检查
        int current = activeCount.get();
        if (current >= maxActive) {
            throw new IllegalStateException(
                    "活跃会话数已达上限: current=%d, max=%d".formatted(current, maxActive));
        }

        // 使用 computeIfAbsent 保证同一 sessionId 只创建一次
        SandboxEntry created = sessions.computeIfAbsent(sessionId, id -> {
            // 再次检查并原子递增（防止并发超限）
            if (activeCount.incrementAndGet() > maxActive) {
                activeCount.decrementAndGet();
                throw new IllegalStateException(
                        "活跃会话数已达上限: max=%d".formatted(maxActive));
            }

            SandboxBooter newBooter = createBooter();
            Path workingDirectory = createWorkingDirectory();
            newBooter.boot(workingDirectory).join();

            log.info("创建新会话: sessionId={}, booterType={}, workingDirectory={}",
                    id, newBooter.type(), workingDirectory);
            return new SandboxEntry(newBooter, Instant.now(), workingDirectory);
        });

        return created.booter();
    }

    /**
     * 销毁指定会话的沙箱实例。
     *
     * <p>调用 booter.shutdown()，删除工作目录，移除 entry。</p>
     *
     * @param sessionId 会话 ID
     */
    public void destroy(String sessionId) {
        SandboxEntry entry = sessions.remove(sessionId);
        if (entry == null) {
            log.debug("会话不存在，跳过销毁: sessionId={}", sessionId);
            return;
        }
        activeCount.decrementAndGet();
        destroyEntry(sessionId, entry);
    }

    /**
     * 清理所有过期会话（由定时任务调用）。
     *
     * <p>扫描所有会话，销毁 lastAccessTime 超过 TTL 的会话。</p>
     */
    public void cleanupExpired() {
        int ttlSeconds = config.getSession().getTtlSeconds();
        Instant threshold = Instant.now().minus(Duration.ofSeconds(ttlSeconds));
        int cleaned = 0;

        for (var entry : sessions.entrySet()) {
            if (entry.getValue().lastAccessTime().isBefore(threshold)) {
                SandboxEntry removed = sessions.remove(entry.getKey());
                if (removed != null) {
                    activeCount.decrementAndGet();
                    destroyEntry(entry.getKey(), removed);
                    cleaned++;
                }
            }
        }

        if (cleaned > 0) {
            log.info("过期会话清理完成: cleaned={}, remaining={}", cleaned, activeCount.get());
        }
    }

    /**
     * 获取当前活跃会话数。
     *
     * @return 活跃会话数
     */
    public int activeCount() {
        return activeCount.get();
    }

    /**
     * 关闭所有会话（应用关闭时调用）。
     *
     * <p>销毁所有活跃会话。</p>
     */
    public void shutdownAll() {
        log.info("开始关闭所有会话: activeCount={}", activeCount.get());

        for (var entry : sessions.entrySet()) {
            SandboxEntry removed = sessions.remove(entry.getKey());
            if (removed != null) {
                activeCount.decrementAndGet();
                destroyEntry(entry.getKey(), removed);
            }
        }

        log.info("所有会话已关闭");
    }

    /**
     * 根据 booterTemplate 类型创建新的 booter 实例。
     */
    private SandboxBooter createBooter() {
        return switch (booterTemplate) {
            case ProcessBooter _ -> new ProcessBooter(config);
            case DockerBooter _ -> new DockerBooter(config);
        };
    }

    /**
     * 在统一工作目录下创建沙箱子目录。
     */
    private Path createWorkingDirectory() {
        try {
            Path sandboxBase = workspaceResolver.resolveAndCreate().resolve("sandbox");
            Files.createDirectories(sandboxBase);
            return Files.createTempDirectory(sandboxBase, "session-");
        } catch (IOException e) {
            throw new IllegalStateException("创建沙箱工作目录失败", e);
        }
    }

    /**
     * 销毁单个会话 entry：shutdown booter + 删除工作目录。
     */
    private void destroyEntry(String sessionId, SandboxEntry entry) {
        try {
            entry.booter().shutdown();
        } catch (Exception e) {
            log.warn("关闭 booter 失败: sessionId={}, error={}", sessionId, e.getMessage());
        }

        try {
            SandboxUtils.deleteDirectoryRecursively(entry.workingDirectory());
        } catch (IOException e) {
            log.warn("删除工作目录失败: sessionId={}, path={}, error={}",
                    sessionId, entry.workingDirectory(), e.getMessage());
        }

        log.info("会话已销毁: sessionId={}", sessionId);
    }
}
