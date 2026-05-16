package com.lifepilot.config.path;

import com.lifepilot.config.bootstrap.BootstrapConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HOME 目录自动迁移器 —— 在应用启动时检测 previousHome 字段，
 * 将旧 HOME 目录的数据迁移到新 HOME 目录。
 *
 * <h3>迁移策略</h3>
 * <ul>
 *   <li>同文件系统：使用 {@link Files#move} 原子重命名（高效）</li>
 *   <li>跨文件系统：递归复制所有文件 → 校验完整性 → 删除原始文件</li>
 * </ul>
 *
 * <h3>失败处理</h3>
 * <p>迁移失败时回滚到旧 HOME 路径，并记录详细错误日志。
 * 回滚意味着将 BootstrapConfig 中的 home 字段恢复为旧路径，
 * 清除 previousHome 字段，使下次启动仍使用旧路径。</p>
 *
 * <h3>重启标记</h3>
 * <p>迁移成功后设置 {@link #isRestartRequired()} 为 true，
 * 上层组件可据此提示用户重启应用。</p>
 *
 * @author zsg
 * @since 2026-06-15
 */
@Component
public class HomeMigrator {

    private static final Logger log = LoggerFactory.getLogger(HomeMigrator.class);

    private final BootstrapConfigService bootstrapConfigService;

    /** 迁移成功后标记需要重启 */
    private final AtomicBoolean restartRequired = new AtomicBoolean(false);

    public HomeMigrator(BootstrapConfigService bootstrapConfigService) {
        this.bootstrapConfigService = bootstrapConfigService;
    }

    /**
     * 执行 HOME 目录迁移（如果需要）。
     *
     * <p>由 {@link ZhiweiPaths} 在初始化阶段、创建目录之前调用。
     * 检测 BootstrapConfig 中的 previousHome 字段，如果存在且与当前 HOME 不同，
     * 则执行数据迁移。</p>
     *
     * @param currentHome 当前配置的 HOME 目录路径
     * @return 迁移后实际应使用的 HOME 路径（成功则为 currentHome，失败则回滚为旧路径）
     */
    public Path migrateIfNeeded(Path currentHome) {
        String previousHomeStr = bootstrapConfigService.getPreviousHome();

        // 无 previousHome 字段，无需迁移
        if (previousHomeStr == null || previousHomeStr.isBlank()) {
            return currentHome;
        }

        Path previousHome = Path.of(PathResolver.expand(previousHomeStr)).toAbsolutePath().normalize();

        // previousHome 与 currentHome 相同，无需迁移（清除残留字段）
        if (previousHome.equals(currentHome)) {
            log.info("previousHome 与当前 HOME 相同，清除 previousHome 字段");
            bootstrapConfigService.savePreviousHome(null);
            return currentHome;
        }

        // 旧目录不存在，无需迁移
        if (!Files.exists(previousHome)) {
            log.warn("previousHome 目录不存在，跳过迁移: {}", previousHome);
            bootstrapConfigService.savePreviousHome(null);
            return currentHome;
        }

        log.info("检测到 HOME 路径变更，开始迁移: {} → {}", previousHome, currentHome);

        try {
            doMigrate(previousHome, currentHome);
            // 迁移成功：清除 previousHome 字段，标记需要重启
            bootstrapConfigService.savePreviousHome(null);
            restartRequired.set(true);
            log.info("HOME 目录迁移成功: {} → {}，需要重启应用", previousHome, currentHome);
            return currentHome;
        } catch (Exception e) {
            // 迁移失败：回滚到旧 HOME 路径
            log.error("HOME 目录迁移失败，回滚到旧路径: {}", previousHome, e);
            rollback(previousHome);
            return previousHome;
        }
    }

    /**
     * 是否需要重启应用（迁移成功后为 true）。
     *
     * @return true 表示迁移已完成，需要重启应用以使新路径完全生效
     */
    public boolean isRestartRequired() {
        return restartRequired.get();
    }

    // ==================== 内部迁移逻辑 ====================

    /**
     * 执行实际的目录迁移操作。
     * 优先尝试原子重命名（同文件系统），失败则回退到递归复制。
     */
    private void doMigrate(Path source, Path target) throws IOException {
        // 确保目标父目录存在
        Files.createDirectories(target.getParent());

        // 如果目标目录已存在且非空，说明可能是部分迁移残留，先清理
        if (Files.exists(target) && isNonEmptyDirectory(target)) {
            log.warn("目标目录已存在且非空，将合并迁移: {}", target);
            crossFileSystemMigrate(source, target);
            return;
        }

        // 如果目标目录存在但为空，删除后再 move
        if (Files.exists(target)) {
            Files.delete(target);
        }

        // 尝试原子重命名（同文件系统）
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            log.info("同文件系统原子迁移成功: {} → {}", source, target);
        } catch (AtomicMoveNotSupportedException e) {
            log.info("原子迁移不支持（跨文件系统），回退到递归复制: {}", e.getMessage());
            crossFileSystemMigrate(source, target);
        }
    }

    /**
     * 跨文件系统迁移：递归复制 → 校验 → 删除原始文件。
     */
    private void crossFileSystemMigrate(Path source, Path target) throws IOException {
        // 第一步：递归复制所有文件
        log.info("开始跨文件系统递归复制: {} → {}", source, target);
        recursiveCopy(source, target);

        // 第二步：校验复制完整性
        log.info("校验复制完整性...");
        verifyCopy(source, target);

        // 第三步：删除原始文件（仅在校验通过后）
        log.info("复制校验通过，删除原始目录: {}", source);
        recursiveDelete(source);
    }

    /**
     * 递归复制目录树。
     */
    private void recursiveCopy(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 校验复制完整性：对比源目录和目标目录中每个文件的大小。
     *
     * @throws IOException 如果校验失败（文件缺失或大小不匹配）
     */
    private void verifyCopy(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                if (!Files.exists(targetFile)) {
                    throw new IOException("复制校验失败：目标文件缺失: " + targetFile);
                }
                long sourceSize = Files.size(file);
                long targetSize = Files.size(targetFile);
                if (sourceSize != targetSize) {
                    throw new IOException(
                            "复制校验失败：文件大小不匹配: " + file +
                                    " (源=" + sourceSize + ", 目标=" + targetSize + ")");
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 递归删除目录树。
     */
    private void recursiveDelete(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 迁移失败时的回滚操作：将 BootstrapConfig 中的 home 恢复为旧路径，清除 previousHome。
     */
    private void rollback(Path previousHome) {
        try {
            bootstrapConfigService.saveHome(previousHome.toString());
            bootstrapConfigService.savePreviousHome(null);
            log.info("回滚完成：home 已恢复为 {}", previousHome);
        } catch (Exception e) {
            log.error("回滚操作也失败了，请手动检查 config.json: {}", e.getMessage(), e);
        }
    }

    /**
     * 判断目录是否非空。
     */
    private boolean isNonEmptyDirectory(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findFirst().isPresent();
        }
    }
}
