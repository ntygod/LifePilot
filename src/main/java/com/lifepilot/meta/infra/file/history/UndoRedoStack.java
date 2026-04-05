package com.lifepilot.meta.infra.file.history;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * 基于 {@link ArrayDeque} 的 undo/redo 双栈。
 *
 * <p>undo 栈深度受 {@code maxDepth} 限制，超出时自动移除最早的快照。
 * 每次 {@link #pushUndo(FileSnapshot)} 时自动清空 redo 栈（新编辑后历史分支失效）。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class UndoRedoStack {

    private final int maxDepth;
    private final Deque<FileSnapshot> undoStack = new ArrayDeque<>();
    private final Deque<FileSnapshot> redoStack = new ArrayDeque<>();

    /**
     * 构造 undo/redo 栈。
     *
     * @param maxDepth undo 栈最大深度，超出后移除最早的快照
     */
    public UndoRedoStack(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth 必须 >= 1，当前值: " + maxDepth);
        }
        this.maxDepth = maxDepth;
    }

    /**
     * 将快照压入 undo 栈，同时清空 redo 栈。
     *
     * <p>如果 undo 栈已满（达到 maxDepth），移除最底部（最早）的快照。</p>
     *
     * @param snapshot 文件快照
     */
    public void pushUndo(FileSnapshot snapshot) {
        if (undoStack.size() >= maxDepth) {
            // 移除最底部（最早）的快照 — push 在头部，最早的在尾部
            undoStack.removeLast();
        }
        undoStack.push(snapshot);
        redoStack.clear();
    }

    /**
     * 将快照压入 undo 栈，但保留 redo 栈不变。
     *
     * <p>专为 redo 操作设计 — redo 时需要把当前内容压入 undo 以便再次撤销，
     * 但不能清空 redo 栈（否则连续 redo 会中断）。</p>
     *
     * @param snapshot 文件快照
     */
    public void pushUndoKeepRedo(FileSnapshot snapshot) {
        if (undoStack.size() >= maxDepth) {
            undoStack.removeLast();
        }
        undoStack.push(snapshot);
    }

    /**
     * 弹出 undo 栈顶快照。
     *
     * @return undo 栈顶的快照，栈空时返回 {@link Optional#empty()}
     */
    public Optional<FileSnapshot> popUndo() {
        if (undoStack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(undoStack.pop());
    }

    /**
     * 将快照压入 redo 栈。
     *
     * @param snapshot 文件快照
     */
    public void pushRedo(FileSnapshot snapshot) {
        redoStack.push(snapshot);
    }

    /**
     * 弹出 redo 栈顶快照。
     *
     * @return redo 栈顶的快照，栈空时返回 {@link Optional#empty()}
     */
    public Optional<FileSnapshot> popRedo() {
        if (redoStack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(redoStack.pop());
    }

    /** 返回 undo 栈当前深度。 */
    public int undoDepth() {
        return undoStack.size();
    }

    /** 返回 redo 栈当前深度。 */
    public int redoDepth() {
        return redoStack.size();
    }
}
