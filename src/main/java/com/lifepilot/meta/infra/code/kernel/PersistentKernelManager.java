package com.lifepilot.meta.infra.code.kernel;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.shell.session.TmuxSessionManager;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 持久内核生命周期管理器。
 *
 * <p>管理所有持久内核实例的创建、复用、重置、检查和关闭。
 * 内置 TTL 清理调度器，自动关闭空闲超时的内核。</p>
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} 管理内核实例。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class PersistentKernelManager {

    private static final Logger log = LoggerFactory.getLogger(PersistentKernelManager.class);

    private final ConcurrentHashMap<String, KernelEntry> kernels = new ConcurrentHashMap<>();
    private final MetaProperties.Infra.Kernel config;
    @Nullable
    private final TmuxSessionManager tmuxSessionManager;
    private final ScheduledExecutorService cleanupScheduler;

    /**
     * 创建持久内核管理器。
     *
     * @param config             内核配置
     * @param tmuxSessionManager tmux 会话管理器（Shell 内核使用，可能为 null）
     */
    public PersistentKernelManager(MetaProperties.Infra.Kernel config,
                                   @Nullable TmuxSessionManager tmuxSessionManager) {
        this.config = config;
        this.tmuxSessionManager = tmuxSessionManager;
        // ScheduledExecutorService 的调度线程必须使用平台线程，不能用虚拟线程
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "kernel-cleanup");
            t.setDaemon(true);
            return t;
        });

        // 定期清理空闲超时的内核
        cleanupScheduler.scheduleAtFixedRate(this::cleanupIdleKernels,
                config.getCleanupIntervalSeconds(), config.getCleanupIntervalSeconds(), TimeUnit.SECONDS);

        log.info("持久内核管理器已启动: maxConcurrent={}, ttlMinutes={}", config.getMaxConcurrentKernels(), config.getTtlMinutes());
    }

    /**
     * 获取或创建持久内核。
     *
     * <p>如果指定 kernelId 的内核已存在且未关闭，直接复用；
     * 否则创建新内核。超过最大并发数时抛出异常。</p>
     *
     * @param kernelId 内核标识
     * @param language 语言（python / javascript / shell）
     * @return 持久内核实例
     * @throws IllegalStateException 超过最大并发数或语言不支持时
     */
    public PersistentKernel getOrCreate(String kernelId, String language) {
        // 尝试复用已有内核
        var existing = kernels.get(kernelId);
        if (existing != null) {
            var kernel = existing.kernel();
            if (kernel.state() == KernelState.CLOSED) {
                // 已关闭，移除后重新创建
                kernels.remove(kernelId);
            } else if (kernel.state() == KernelState.ERROR && kernel instanceof ProcessKernelBase processKernel) {
                // ERROR 态尝试自动重启
                log.info("检测到内核 ERROR 态，尝试自动重启: kernelId={}", kernelId);
                if (processKernel.tryRestart()) {
                    existing.touch();
                    return kernel;
                }
                // 重启失败，移除后重新创建
                kernels.remove(kernelId);
            } else {
                existing.touch();
                return kernel;
            }
        }

        // 检查并发限制
        long activeCount = kernels.values().stream()
                .filter(e -> e.kernel().state() != KernelState.CLOSED)
                .count();
        if (activeCount >= config.getMaxConcurrentKernels()) {
            throw new IllegalStateException("持久内核数已达上限: " + config.getMaxConcurrentKernels());
        }

        // 创建新内核
        PersistentKernel kernel = createKernel(kernelId, language);
        kernels.put(kernelId, new KernelEntry(kernel, new AtomicReference<>(Instant.now())));
        log.info("持久内核已创建: kernelId={}, language={}", kernelId, language);

        return kernel;
    }

    /**
     * 关闭指定内核。
     *
     * @param kernelId 内核标识
     */
    public void closeKernel(String kernelId) {
        var entry = kernels.remove(kernelId);
        if (entry != null) {
            entry.kernel().close();
            log.info("持久内核已关闭: kernelId={}", kernelId);
        }
    }

    /**
     * 重置指定内核（清空状态，不关闭进程）。
     *
     * @param kernelId 内核标识
     * @throws IllegalArgumentException 内核不存在时
     */
    public void resetKernel(String kernelId) {
        var entry = kernels.get(kernelId);
        if (entry == null) {
            throw new IllegalArgumentException("内核不存在: kernelId=" + kernelId);
        }
        entry.kernel().reset();
        entry.touch();
        log.info("持久内核已重置: kernelId={}", kernelId);
    }

    /**
     * 检查指定内核状态。
     *
     * @param kernelId 内核标识
     * @return 内核状态信息
     * @throws IllegalArgumentException 内核不存在时
     */
    public Map<String, String> inspectKernel(String kernelId) {
        var entry = kernels.get(kernelId);
        if (entry == null) {
            throw new IllegalArgumentException("内核不存在: kernelId=" + kernelId);
        }
        entry.touch();
        return entry.kernel().inspect();
    }

    /**
     * 列出所有活跃内核信息。
     *
     * @return 内核信息列表，每个条目包含 kernelId、state、idleSeconds
     */
    public List<Map<String, Object>> listKernels() {
        var now = Instant.now();
        return kernels.entrySet().stream()
                .map(e -> {
                    var info = new LinkedHashMap<String, Object>();
                    info.put("kernelId", e.getKey());
                    var kernel = e.getValue().kernel();
                    info.put("state", kernel.state().name());
                    long idleSeconds = Duration.between(e.getValue().lastAccessTime().get(), now).toSeconds();
                    info.put("idleSeconds", idleSeconds);
                    return (Map<String, Object>) info;
                })
                .toList();
    }

    /**
     * 获取内核配置。
     *
     * @return 内核配置
     */
    public MetaProperties.Infra.Kernel getConfig() {
        return config;
    }

    /**
     * 关闭管理器，终止所有内核和清理调度器。
     */
    public void shutdown() {
        cleanupScheduler.shutdownNow();
        kernels.forEach((id, entry) -> {
            try {
                entry.kernel().close();
            } catch (Exception e) {
                log.debug("关闭内核失败: kernelId={}, error={}", id, e.getMessage());
            }
        });
        kernels.clear();
        log.info("持久内核管理器已关闭");
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    /**
     * 根据语言创建对应的内核实例。
     */
    private PersistentKernel createKernel(String kernelId, String language) {
        return switch (language.toLowerCase()) {
            case "python" -> new PythonKernel(kernelId, config.getPythonRuntime(), config.getMaxOutputChars());
            case "javascript", "js" -> new JavaScriptKernel(kernelId, config.getNodeRuntime(), config.getMaxOutputChars());
            case "shell", "bash", "sh" -> new ShellKernel(kernelId, tmuxSessionManager, config.getMaxOutputChars());
            default -> throw new IllegalStateException("不支持的内核语言: " + language);
        };
    }

    /**
     * 清理空闲超时的内核。
     */
    void cleanupIdleKernels() {
        var now = Instant.now();
        var ttlMinutes = config.getTtlMinutes();

        kernels.forEach((id, entry) -> {
            var idleMinutes = Duration.between(entry.lastAccessTime().get(), now).toMinutes();
            if (idleMinutes >= ttlMinutes) {
                try {
                    entry.kernel().close();
                    log.info("持久内核空闲超时清理: kernelId={}, idleMinutes={}", id, idleMinutes);
                } catch (Exception e) {
                    log.debug("清理内核失败: kernelId={}, error={}", id, e.getMessage());
                }
                kernels.remove(id);
            }
        });
    }

    /**
     * 内核条目 — 包含内核实例和最后访问时间。
     */
    record KernelEntry(PersistentKernel kernel, AtomicReference<Instant> lastAccessTime) {
        void touch() {
            lastAccessTime.set(Instant.now());
        }
    }
}
