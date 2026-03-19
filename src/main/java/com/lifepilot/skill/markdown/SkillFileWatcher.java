package com.lifepilot.skill.markdown;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Skill 文件夹监听器 — 使用 WatchService 监听 Skill 根目录及一级子目录变更，触发热加载。
 *
 * <p>与旧版 YAML 文件监听器不同，本实现监听文件夹级别事件：
 * <ul>
 *   <li>根目录：监听子目录的 CREATE/DELETE 事件</li>
 *   <li>子目录：监听 SKILL.md 的 CREATE/MODIFY 事件</li>
 * </ul>
 *
 * <p>运行在 Virtual Thread 上，使用 {@link ScheduledExecutorService} 实现防抖。
 * {@link AtomicBoolean} 防止并发重载。解析失败时保留上一个有效版本。</p>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class SkillFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillFileWatcher.class);

    private final MarkdownSkillLoader markdownSkillLoader;
    private final SkillRegistry skillRegistry;
    private final SkillConfigProperties config;
    private final AtomicBoolean reloading = new AtomicBoolean(false);
    private final ScheduledExecutorService debounceExecutor;
    private volatile boolean running = true;

    /** Skill 文件夹路径 → Skill ID 映射，用于 DELETE 事件时注销对应 Skill。 */
    private final ConcurrentHashMap<Path, String> folderSkillIdMap = new ConcurrentHashMap<>();

    /** Skill 文件夹路径 → 防抖 ScheduledFuture 映射，用于取消前一次防抖任务。 */
    private final ConcurrentHashMap<Path, ScheduledFuture<?>> pendingReloads = new ConcurrentHashMap<>();

    /** WatchKey → 被监听目录路径映射，用于识别事件来源目录。 */
    private final ConcurrentHashMap<WatchKey, Path> watchKeyPathMap = new ConcurrentHashMap<>();

    /** WatchService 引用，destroy 时关闭。 */
    private volatile WatchService watchService;

    public SkillFileWatcher(MarkdownSkillLoader markdownSkillLoader,
                            SkillRegistry skillRegistry,
                            SkillConfigProperties config,
                            SharedScheduler sharedScheduler) {
        this.markdownSkillLoader = markdownSkillLoader;
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.debounceExecutor = sharedScheduler.debounce();
    }

    /**
     * 启动文件监听。在 ApplicationReadyEvent 后由 SkillAutoConfiguration 调用。
     *
     * <p>流程：
     * <ol>
     *   <li>确保 Skill 根目录存在</li>
     *   <li>触发 MarkdownSkillLoader.loadAll() 初始加载</li>
     *   <li>构建初始 folder→skillId 映射</li>
     *   <li>在 Virtual Thread 中启动 WatchService 循环</li>
     * </ol>
     */
    public void start() {
        Path skillsDir = markdownSkillLoader.getSkillsDirectory();

        // 1. 确保目录存在
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.warn("创建 Skill 目录失败: path={}, error={}", skillsDir, e.getMessage());
        }

        // 2. 初始加载
        int loaded = markdownSkillLoader.loadAll();
        log.info("Skill 初始加载完成: count={}", loaded);

        // 3. 构建初始 folder→skillId 映射
        buildInitialFolderMapping(skillsDir);

        // 4. 在 Virtual Thread 中启动 WatchService 循环
        Thread.ofVirtual().name("skill-file-watcher").start(() -> watchLoop(skillsDir));
    }

    public void destroy() {
        running = false;
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
     *
     * <p>同时监听根目录（子目录 CREATE/DELETE）和每个 Skill 子目录（SKILL.md CREATE/MODIFY）。</p>
     */
    private void watchLoop(Path skillsDir) {
        try {
            watchService = FileSystems.getDefault().newWatchService();

            // 注册根目录监听（子目录的 CREATE/DELETE）
            registerWatch(skillsDir);

            // 注册所有已存在的 Skill 子目录监听（SKILL.md 的 CREATE/MODIFY）
            registerExistingSubdirectories(skillsDir);

            // 注册 auto/ 子目录监听（自生成 Skill 热加载）
            Path autoDir = skillsDir.resolve("auto");
            if (Files.exists(autoDir) && Files.isDirectory(autoDir)) {
                registerWatch(autoDir);
                registerExistingSubdirectories(autoDir);
            }

            log.info("Skill 文件夹监听已启动: path={}", skillsDir);

            while (running) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (ClosedWatchServiceException e) {
                    // destroy() 关闭了 WatchService，正常退出
                    break;
                }

                Path watchedDir = watchKeyPathMap.get(key);
                if (watchedDir == null) {
                    key.reset();
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path fullPath = watchedDir.resolve(fileName);

                    handleEvent(kind, fullPath, watchedDir, skillsDir);
                }

                boolean valid = key.reset();
                if (!valid) {
                    // WatchKey 失效，从映射中移除
                    watchKeyPathMap.remove(key);
                    log.debug("WatchKey 已失效: dir={}", watchedDir);
                    // 如果根目录的 WatchKey 失效，停止监听
                    if (watchedDir.equals(skillsDir)) {
                        log.warn("根目录 WatchKey 已失效，停止文件监听");
                        break;
                    }
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
     * 处理文件系统事件，根据事件来源目录分发处理。
     *
     * @param kind       事件类型
     * @param fullPath   变更文件/目录的完整路径
     * @param watchedDir 产生事件的被监听目录
     * @param skillsDir  Skill 根目录
     */
    void handleEvent(WatchEvent.Kind<?> kind, Path fullPath, Path watchedDir, Path skillsDir) {
        if (watchedDir.equals(skillsDir)) {
            // 根目录事件：子目录的 CREATE/DELETE
            handleRootDirEvent(kind, fullPath, skillsDir);
        } else {
            // 子目录事件：SKILL.md 的 CREATE/MODIFY
            handleSubDirEvent(kind, fullPath, watchedDir);
        }
    }

    /**
     * 处理根目录事件 — 子目录的创建和删除。
     *
     * <p>新子目录创建时检查是否包含 SKILL.md，如果包含则加载并注册 WatchService。
     * 子目录删除时注销对应 Skill。</p>
     */
    private void handleRootDirEvent(WatchEvent.Kind<?> kind, Path fullPath, Path skillsDir) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            // 新子目录创建 — 延迟检查（文件可能还在写入中）
            if (Files.isDirectory(fullPath)) {
                // auto/ 目录创建时，注册其内部监听
                if (fullPath.getFileName().toString().equals("auto")) {
                    registerWatch(fullPath);
                    registerExistingSubdirectories(fullPath);
                    return;
                }
                handleNewSubdirectory(fullPath);
            }
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            // 子目录删除 — 注销对应 Skill
            handleFolderDelete(fullPath);
        }
    }

    /**
     * 处理子目录事件 — SKILL.md 的创建和修改。
     */
    private void handleSubDirEvent(WatchEvent.Kind<?> kind, Path fullPath, Path watchedDir) {
        String skillFilename = config.getSkillFilename();

        // 只处理 SKILL.md 文件
        if (!fullPath.getFileName().toString().equals(skillFilename)) {
            return;
        }

        if (kind == StandardWatchEventKinds.ENTRY_CREATE
                || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            // SKILL.md 创建或修改 — 防抖后重新加载
            scheduleDebounced(watchedDir);
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            // SKILL.md 被删除 — 注销对应 Skill
            handleFolderDelete(watchedDir);
        }
    }

    /**
     * 处理新子目录创建：检查是否包含 SKILL.md，如果包含则加载并注册 WatchService。
     */
    private void handleNewSubdirectory(Path subDir) {
        // 注册子目录的 WatchService
        registerWatch(subDir);

        Path skillFile = subDir.resolve(config.getSkillFilename());
        if (Files.exists(skillFile)) {
            // 包含 SKILL.md，防抖后加载
            scheduleDebounced(subDir);
        } else {
            log.debug("新子目录不包含 {}: dir={}", config.getSkillFilename(), subDir);
        }
    }

    /**
     * 处理文件夹删除事件：从映射中查找 skillId 并注销。
     */
    private void handleFolderDelete(Path folderPath) {
        String skillId = folderSkillIdMap.remove(folderPath);
        if (skillId != null) {
            skillRegistry.unregister(skillId);
            log.info("Skill 已注销（文件夹删除）: folder={}, skillId={}", folderPath, skillId);
        } else {
            log.debug("删除的文件夹未在映射中找到: folder={}", folderPath);
        }
    }

    /**
     * 防抖调度：取消前一次待执行任务，重新延迟执行。
     */
    private void scheduleDebounced(Path skillFolder) {
        // 取消前一次防抖任务
        ScheduledFuture<?> previous = pendingReloads.get(skillFolder);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }

        long debounceMs = config.getHotReloadDebounceMs();
        ScheduledFuture<?> future = debounceExecutor.schedule(
                () -> executeReload(skillFolder),
                debounceMs,
                TimeUnit.MILLISECONDS
        );
        pendingReloads.put(skillFolder, future);
    }

    /**
     * 执行单个 Skill 文件夹重载，使用 AtomicBoolean 防止并发重载。
     *
     * <p>解析失败时保留上一个有效版本（不注销 Skill）。</p>
     */
    private void executeReload(Path skillFolder) {
        if (!reloading.compareAndSet(false, true)) {
            log.debug("重载正在进行中，跳过: folder={}", skillFolder);
            return;
        }
        try {
            var result = markdownSkillLoader.loadFolder(skillFolder);
            if (result.isPresent()) {
                SkillDefinition definition = result.get();
                skillRegistry.register(definition);
                // 更新 folder→skillId 映射
                folderSkillIdMap.put(skillFolder, definition.id());
                log.info("Skill 热加载成功: folder={}, skillId={}", skillFolder, definition.id());
            } else {
                // 解析失败 — 保留上一个有效版本，不注销
                log.warn("Skill 热加载失败，保留上一个有效版本: folder={}", skillFolder);
            }
        } catch (Exception e) {
            log.error("Skill 热加载异常: folder={}, error={}", skillFolder, e.getMessage());
        } finally {
            reloading.set(false);
            pendingReloads.remove(skillFolder);
        }
    }

    /**
     * 注册目录到 WatchService。
     */
    private void registerWatch(Path dir) {
        try {
            WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchKeyPathMap.put(key, dir);
            log.debug("已注册 WatchService: dir={}", dir);
        } catch (IOException e) {
            log.warn("注册 WatchService 失败: dir={}, error={}", dir, e.getMessage());
        }
    }

    /**
     * 注册所有已存在的 Skill 子目录到 WatchService。
     */
    private void registerExistingSubdirectories(Path skillsDir) {
        try (var entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory)
                    .forEach(this::registerWatch);
        } catch (IOException e) {
            log.warn("扫描子目录失败: path={}, error={}", skillsDir, e.getMessage());
        }
    }

    /**
     * 构建初始 folder→skillId 映射。
     *
     * <p>扫描 Skill 根目录下所有包含 SKILL.md 的子目录，
     * 解析 SKILL.md 获取 skillId 并建立映射关系。</p>
     */
    private void buildInitialFolderMapping(Path skillsDir) {
        String skillFilename = config.getSkillFilename();
        try (var entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(skillFilename)))
                    .forEach(dir -> {
                        var result = markdownSkillLoader.loadFolder(dir);
                        result.ifPresent(def -> folderSkillIdMap.put(dir, def.id()));
                    });
        } catch (IOException e) {
            log.warn("构建初始文件夹映射失败: path={}, error={}", skillsDir, e.getMessage());
        }
    }
}
