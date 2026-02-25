package com.lifepilot.skill.yaml;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Skill 文件监听器 — 使用 WatchService 监听目录变更，触发热加载。
 *
 * <p>运行在 Virtual Thread 上，使用 {@link ScheduledExecutorService} 实现防抖。
 * {@link AtomicBoolean} 防止并发重载。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SkillFileWatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SkillFileWatcher.class);

    private final YamlSkillLoader yamlSkillLoader;
    private final SkillRegistry skillRegistry;
    private final SkillConfigProperties config;
    private final AtomicBoolean reloading = new AtomicBoolean(false);
    private final ScheduledExecutorService debounceExecutor;
    private volatile boolean running = true;

    /** 文件路径 → Skill ID 映射，用于 DELETE 事件时注销对应 Skill。 */
    private final ConcurrentHashMap<Path, String> fileSkillIdMap = new ConcurrentHashMap<>();

    /** 文件路径 → 防抖 ScheduledFuture 映射，用于取消前一次防抖任务。 */
    private final ConcurrentHashMap<Path, ScheduledFuture<?>> pendingReloads = new ConcurrentHashMap<>();

    /** WatchService 引用，destroy 时关闭。 */
    private volatile WatchService watchService;

    public SkillFileWatcher(YamlSkillLoader yamlSkillLoader,
                            SkillRegistry skillRegistry,
                            SkillConfigProperties config) {
        this.yamlSkillLoader = yamlSkillLoader;
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skill-debounce");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动文件监听。在 ApplicationReadyEvent 后由 SkillAutoConfiguration 调用。
     *
     * <p>流程：
     * <ol>
     *   <li>确保 ~/.lifepilot/skills/ 目录存在</li>
     *   <li>触发 YamlSkillLoader.loadAll() 初始加载</li>
     *   <li>在 Virtual Thread 中启动 WatchService 循环</li>
     * </ol></p>
     */
    public void start() {
        Path skillsDir = Path.of(config.getDirectory());

        // 1. 确保目录存在
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.warn("创建 Skill 目录失败: path={}, error={}", skillsDir, e.getMessage());
        }

        // 2. 初始加载
        int loaded = yamlSkillLoader.loadAll();
        log.info("Skill 初始加载完成: count={}", loaded);

        // 3. 构建初始 file→skillId 映射
        buildInitialFileMapping(skillsDir);

        // 4. 在 Virtual Thread 中启动 WatchService 循环
        Thread.ofVirtual().name("skill-file-watcher").start(() -> watchLoop(skillsDir));
    }

    /**
     * 处理文件变更事件。
     *
     * <p>防抖逻辑：收到事件后延迟 debounceMs 执行，期间新事件重置计时器。
     * AtomicBoolean 确保同一时刻只有一个重载在进行。</p>
     *
     * @param kind     事件类型
     * @param filePath 变更文件的完整路径
     */
    void handleFileEvent(WatchEvent.Kind<?> kind, Path filePath) {
        // 只处理 .yml / .yaml 文件
        if (!isYamlFile(filePath)) {
            return;
        }

        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            // DELETE 事件直接处理，不需要防抖
            handleDelete(filePath);
            return;
        }

        // CREATE / MODIFY 事件使用防抖
        scheduleDebounced(filePath);
    }

    @Override
    public void destroy() {
        running = false;
        debounceExecutor.shutdown();
        // 关闭 WatchService，使 watchKey.take() 抛出 ClosedWatchServiceException 退出循环
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("关闭 WatchService 失败: error={}", e.getMessage());
            }
        }
        log.info("SkillFileWatcher 已停止");
    }

    /**
     * 判断当前监听器是否正在运行。
     *
     * @return true 表示正在运行
     */
    boolean isRunning() {
        return running;
    }

    // ==================== 内部方法 ====================

    /**
     * WatchService 监听循环，运行在 Virtual Thread 上。
     */
    private void watchLoop(Path skillsDir) {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            skillsDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            log.info("Skill 文件监听已启动: path={}", skillsDir);

            while (running) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (ClosedWatchServiceException e) {
                    // destroy() 关闭了 WatchService，正常退出
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path fullPath = skillsDir.resolve(fileName);

                    handleFileEvent(kind, fullPath);
                }

                boolean valid = key.reset();
                if (!valid) {
                    log.warn("WatchKey 已失效，停止文件监听");
                    break;
                }
            }
        } catch (IOException e) {
            log.error("WatchService 启动失败: error={}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Skill 文件监听线程被中断");
        }
    }

    /**
     * 防抖调度：取消前一次待执行任务，重新延迟执行。
     */
    private void scheduleDebounced(Path filePath) {
        // 取消前一次防抖任务
        ScheduledFuture<?> previous = pendingReloads.get(filePath);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }

        long debounceMs = config.getHotReloadDebounceMs();
        ScheduledFuture<?> future = debounceExecutor.schedule(
                () -> executeReload(filePath),
                debounceMs,
                TimeUnit.MILLISECONDS
        );
        pendingReloads.put(filePath, future);
    }

    /**
     * 执行单文件重载，使用 AtomicBoolean 防止并发重载。
     */
    private void executeReload(Path filePath) {
        if (!reloading.compareAndSet(false, true)) {
            log.debug("重载正在进行中，跳过: file={}", filePath);
            return;
        }
        try {
            var result = yamlSkillLoader.loadFile(filePath);
            if (result.isPresent()) {
                SkillDefinition definition = result.get();
                skillRegistry.register(definition);
                // 更新 file→skillId 映射
                fileSkillIdMap.put(filePath, definition.id());
                log.info("Skill 热加载成功: file={}, skillId={}", filePath, definition.id());
            } else {
                log.warn("Skill 热加载失败，文件解析错误: file={}", filePath);
            }
        } catch (Exception e) {
            log.error("Skill 热加载异常: file={}, error={}", filePath, e.getMessage());
        } finally {
            reloading.set(false);
            pendingReloads.remove(filePath);
        }
    }

    /**
     * 处理文件删除事件：从映射中查找 skillId 并注销。
     */
    private void handleDelete(Path filePath) {
        String skillId = fileSkillIdMap.remove(filePath);
        if (skillId != null) {
            skillRegistry.unregister(skillId);
            log.info("Skill 已注销（文件删除）: file={}, skillId={}", filePath, skillId);
        } else {
            log.debug("删除的文件未在映射中找到: file={}", filePath);
        }
    }

    /**
     * 构建初始 file→skillId 映射。
     */
    private void buildInitialFileMapping(Path skillsDir) {
        try (var files = Files.list(skillsDir)) {
            files.filter(Files::isRegularFile)
                    .filter(this::isYamlFile)
                    .forEach(file -> {
                        var result = yamlSkillLoader.loadFile(file);
                        result.ifPresent(def -> fileSkillIdMap.put(file, def.id()));
                    });
        } catch (IOException e) {
            log.warn("构建初始文件映射失败: path={}, error={}", skillsDir, e.getMessage());
        }
    }

    /**
     * 判断文件是否为 YAML 文件。
     */
    private boolean isYamlFile(Path filePath) {
        String name = filePath.getFileName().toString().toLowerCase();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
