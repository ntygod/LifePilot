package com.lifepilot.meta.infra.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lifepilot.tool.artifact.ArtifactFilter;
import com.lifepilot.tool.artifact.ArtifactFilterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作目录文件快照工具 — 用于 {@code shell.exec} / {@code code.exec} 等通用执行器
 * 在执行前后对比 cwd 文件列表，识别"新增 + 修改"作为产物候选。
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>用 {@link Files#walk(Path, java.nio.file.FileVisitOption...)} 递归列举所有
 *       常规文件，过滤目录与符号链接</li>
 *   <li>每个文件用 {@link ArtifactFilter#accept(Path, long, ArtifactFilterConfig)}
 *       过滤噪声（{@code .git} / {@code node_modules} / {@code *.tmp} 等）；
 *       不通过的文件不进入快照</li>
 *   <li>快照值是 {@link FileSnapshot}（mtime + size），diff 时任一变化都记为产物候选</li>
 * </ul>
 *
 * <p>本类仅做读快照，不持有状态，全部静态方法；线程安全。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
public final class WorkspaceDiffSnapshot {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceDiffSnapshot.class);

    private WorkspaceDiffSnapshot() {
        // utility class
    }

    /**
     * 单个文件的快照值。
     *
     * @param mtime 最后修改时间
     * @param size  字节数
     */
    public record FileSnapshot(Instant mtime, long size) {}

    /**
     * 对 cwd 递归生成文件快照，应用 {@link ArtifactFilter} 过滤规则。
     *
     * <p>使用 {@link java.nio.file.FileVisitor} + {@code SKIP_SUBTREE} 在进入排除目录前
     * 直接跳过子树遍历，避免 {@code node_modules} / {@code .git} 等大目录的无谓 I/O。</p>
     *
     * @param cwd    工作目录；非目录或不存在时返回空 Map
     * @param filter 过滤配置
     * @return path → FileSnapshot 映射；调用方应保留引用用于 diff 对比
     */
    public static Map<Path, FileSnapshot> take(Path cwd, ArtifactFilterConfig filter) {
        if (cwd == null || !Files.isDirectory(cwd)) {
            return Map.of();
        }
        Map<Path, FileSnapshot> snapshot = new LinkedHashMap<>();
        try {
            Files.walkFileTree(cwd, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    // 根目录始终进入
                    if (dir.equals(cwd)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    // 排除目录：直接跳过子树，避免无谓 I/O
                    Path dirName = dir.getFileName();
                    if (dirName != null && filter.excludedDirs().contains(dirName.toString())) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    // 隐藏目录也跳过
                    if (dirName != null && dirName.toString().startsWith(".")) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    try {
                        long size = attrs.size();
                        Path normalized = path.toAbsolutePath().normalize();
                        if (!ArtifactFilter.accept(normalized, size, filter)) {
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                        Instant mtime = attrs.lastModifiedTime().toInstant();
                        snapshot.put(normalized, new FileSnapshot(mtime, size));
                    } catch (Exception ignored) {
                        // 单文件读取失败不影响整体快照
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("workspace 快照失败: cwd={}, error={}", cwd, e.getMessage());
            return Map.of();
        }
        return snapshot;
    }

    /**
     * 对比执行前后两个快照，返回新增 + mtime/size 变化的文件路径列表。
     *
     * <p>不返回被删除的文件（产物语义只关注「执行后存在的产物」）。</p>
     *
     * @param before 执行前快照
     * @param after  执行后快照
     * @return 变更文件路径列表（按 after 中的迭代顺序）
     */
    public static List<Path> diff(Map<Path, FileSnapshot> before, Map<Path, FileSnapshot> after) {
        if (after == null || after.isEmpty()) {
            return List.of();
        }
        List<Path> changed = new ArrayList<>();
        for (Map.Entry<Path, FileSnapshot> entry : after.entrySet()) {
            Path key = entry.getKey();
            FileSnapshot afterSnap = entry.getValue();
            FileSnapshot beforeSnap = before != null ? before.get(key) : null;

            if (beforeSnap == null) {
                changed.add(key);  // 新增
            } else if (!beforeSnap.mtime().equals(afterSnap.mtime())
                    || beforeSnap.size() != afterSnap.size()) {
                changed.add(key);  // 修改
            }
        }
        return changed;
    }
}
