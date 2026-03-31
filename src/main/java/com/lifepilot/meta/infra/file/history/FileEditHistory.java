package com.lifepilot.meta.infra.file.history;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文件编辑历史管理器 — 维护每个文件的 undo/redo 栈和会话起始快照。
 *
 * <p>使用 {@link ConcurrentHashMap} 按规范化路径管理独立的 {@link UndoRedoStack}。
 * 首次修改文件时捕获会话起始快照，用于 {@link #diff(Path)} 和 {@link #diffAll()} 计算。</p>
 *
 * <p>大文件（超过 {@code maxSnapshotSizeBytes}）将被跳过，不记录快照。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class FileEditHistory {

    private static final Logger log = LoggerFactory.getLogger(FileEditHistory.class);

    private final int undoMaxDepth;
    private final long maxSnapshotSizeBytes;

    /** 每个文件路径对应的 undo/redo 栈。 */
    private final ConcurrentHashMap<String, UndoRedoStack> stacks = new ConcurrentHashMap<>();

    /** 会话起始快照 — 首次修改时捕获，用于 diff 计算。 */
    private final ConcurrentHashMap<String, byte[]> sessionStartSnapshots = new ConcurrentHashMap<>();

    /**
     * 构造文件编辑历史管理器。
     *
     * @param undoMaxDepth        undo 栈最大深度
     * @param maxSnapshotSizeBytes 单文件快照最大字节数，超过则跳过
     */
    public FileEditHistory(int undoMaxDepth, long maxSnapshotSizeBytes) {
        this.undoMaxDepth = undoMaxDepth;
        this.maxSnapshotSizeBytes = maxSnapshotSizeBytes;
    }

    /**
     * 在修改文件前捕获当前内容快照。
     *
     * <p>如果是首次修改该文件，同时保存会话起始快照。
     * 文件不存在、不可读或超过大小限制时静默跳过。</p>
     *
     * @param filePath 要修改的文件路径
     */
    public void captureBeforeModify(Path filePath) {
        Path normalized = filePath.toAbsolutePath().normalize();
        String key = normalized.toString();

        if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
            log.debug("文件不存在或非普通文件，跳过快照捕获: {}", key);
            return;
        }

        try {
            long size = Files.size(normalized);
            if (size > maxSnapshotSizeBytes) {
                log.debug("文件超过快照大小限制({} > {})，跳过: {}", size, maxSnapshotSizeBytes, key);
                return;
            }

            byte[] content = Files.readAllBytes(normalized);

            // 首次修改时保存会话起始快照
            sessionStartSnapshots.putIfAbsent(key, content);

            // 压入 undo 栈
            var stack = stacks.computeIfAbsent(key, k -> new UndoRedoStack(undoMaxDepth));
            stack.pushUndo(new FileSnapshot(normalized, content, Instant.now()));

            log.debug("文件快照已捕获: path={}, size={}, undoDepth={}", key, content.length, stack.undoDepth());
        } catch (IOException e) {
            log.warn("捕获文件快照失败: path={}, error={}", key, e.getMessage());
        }
    }

    /**
     * 撤销文件的最近一次修改。
     *
     * <p>从 undo 栈弹出快照，将当前文件内容压入 redo 栈，然后将快照内容写回文件。</p>
     *
     * @param filePath 要撤销的文件路径
     * @return 恢复后的快照，undo 栈为空时返回 {@link Optional#empty()}
     * @throws IOException 文件读写失败时抛出
     */
    public Optional<FileSnapshot> undo(Path filePath) throws IOException {
        Path normalized = filePath.toAbsolutePath().normalize();
        String key = normalized.toString();

        var stack = stacks.get(key);
        if (stack == null) {
            return Optional.empty();
        }

        var snapshotOpt = stack.popUndo();
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }

        // 保存当前文件内容到 redo 栈
        if (Files.exists(normalized)) {
            byte[] currentContent = Files.readAllBytes(normalized);
            stack.pushRedo(new FileSnapshot(normalized, currentContent, Instant.now()));
        }

        // 写回快照内容
        var snapshot = snapshotOpt.get();
        Files.write(normalized, snapshot.content());

        log.info("文件撤销成功: path={}, undoDepth={}, redoDepth={}", key, stack.undoDepth(), stack.redoDepth());
        return snapshotOpt;
    }

    /**
     * 重做文件的最近一次撤销操作。
     *
     * <p>从 redo 栈弹出快照，将当前文件内容压入 undo 栈（不清空 redo），然后将快照内容写回文件。</p>
     *
     * @param filePath 要重做的文件路径
     * @return 恢复后的快照，redo 栈为空时返回 {@link Optional#empty()}
     * @throws IOException 文件读写失败时抛出
     */
    public Optional<FileSnapshot> redo(Path filePath) throws IOException {
        Path normalized = filePath.toAbsolutePath().normalize();
        String key = normalized.toString();

        var stack = stacks.get(key);
        if (stack == null) {
            return Optional.empty();
        }

        var snapshotOpt = stack.popRedo();
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }

        // 保存当前文件内容到 undo 栈（注意：这里直接操作底层栈，不能用 pushUndo 因为它会清空 redo）
        if (Files.exists(normalized)) {
            byte[] currentContent = Files.readAllBytes(normalized);
            // 直接压入 undo 栈，不清空 redo 栈
            var undoSnapshot = new FileSnapshot(normalized, currentContent, Instant.now());
            // 用一个临时引用来实现"只压入 undo 不清空 redo"
            // 我们先 pop 所有 redo，pushUndo 后再恢复 redo
            var redoBackup = new java.util.ArrayDeque<FileSnapshot>();
            Optional<FileSnapshot> r;
            while ((r = stack.popRedo()).isPresent()) {
                redoBackup.push(r.get());
            }
            stack.pushUndo(undoSnapshot); // 这会清空 redo
            // 恢复 redo 栈
            while (!redoBackup.isEmpty()) {
                stack.pushRedo(redoBackup.pop());
            }
        }

        // 写回快照内容
        var snapshot = snapshotOpt.get();
        Files.write(normalized, snapshot.content());

        log.info("文件重做成功: path={}, undoDepth={}, redoDepth={}", key, stack.undoDepth(), stack.redoDepth());
        return snapshotOpt;
    }

    /**
     * 计算指定文件自会话开始以来的 unified diff。
     *
     * @param filePath 要比较的文件路径
     * @return unified diff 字符串，无变更时返回空字符串
     * @throws IOException 文件读取失败时抛出
     */
    public String diff(Path filePath) throws IOException {
        Path normalized = filePath.toAbsolutePath().normalize();
        String key = normalized.toString();

        byte[] original = sessionStartSnapshots.get(key);
        if (original == null) {
            return ""; // 该文件没有被修改过
        }

        byte[] current;
        if (Files.exists(normalized)) {
            current = Files.readAllBytes(normalized);
        } else {
            current = new byte[0]; // 文件已被删除
        }

        // 内容相同则无差异
        if (Arrays.equals(original, current)) {
            return "";
        }

        return computeUnifiedDiff(key, original, current);
    }

    /**
     * 计算所有被修改文件自会话开始以来的 unified diff。
     *
     * @return 所有文件的 unified diff 拼接，无变更时返回空字符串
     */
    public String diffAll() {
        var sb = new StringBuilder();

        for (var entry : sessionStartSnapshots.entrySet()) {
            String key = entry.getKey();
            byte[] original = entry.getValue();

            byte[] current;
            Path filePath = Path.of(key);
            try {
                if (Files.exists(filePath)) {
                    current = Files.readAllBytes(filePath);
                } else {
                    current = new byte[0];
                }
            } catch (IOException e) {
                log.warn("读取文件失败，跳过 diff: path={}, error={}", key, e.getMessage());
                continue;
            }

            if (Arrays.equals(original, current)) {
                continue;
            }

            String fileDiff = computeUnifiedDiff(key, original, current);
            if (!fileDiff.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(fileDiff);
            }
        }

        return sb.toString();
    }

    /**
     * 获取指定文件的 undo 栈深度。
     *
     * @param filePath 文件路径
     * @return undo 栈深度，文件未被修改过时返回 0
     */
    public int undoDepth(Path filePath) {
        String key = filePath.toAbsolutePath().normalize().toString();
        var stack = stacks.get(key);
        return stack != null ? stack.undoDepth() : 0;
    }

    /**
     * 获取指定文件的 redo 栈深度。
     *
     * @param filePath 文件路径
     * @return redo 栈深度，文件未被修改过时返回 0
     */
    public int redoDepth(Path filePath) {
        String key = filePath.toAbsolutePath().normalize().toString();
        var stack = stacks.get(key);
        return stack != null ? stack.redoDepth() : 0;
    }

    /**
     * 计算两段内容之间的 unified diff。
     *
     * @param fileName   文件名（用于 diff 头部）
     * @param original   原始内容
     * @param current    当前内容
     * @return unified diff 字符串
     */
    private String computeUnifiedDiff(String fileName, byte[] original, byte[] current) {
        List<String> originalLines = new String(original, StandardCharsets.UTF_8).lines().collect(Collectors.toList());
        List<String> currentLines = new String(current, StandardCharsets.UTF_8).lines().collect(Collectors.toList());

        Patch<String> patch = DiffUtils.diff(originalLines, currentLines);
        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + fileName,
                "b/" + fileName,
                originalLines,
                patch,
                3 // 上下文行数
        );

        return String.join("\n", unifiedDiff);
    }
}
