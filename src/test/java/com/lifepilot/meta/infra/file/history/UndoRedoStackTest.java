package com.lifepilot.meta.infra.file.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UndoRedoStack} 单元测试。
 *
 * @author zsg
 * @since 2026-03-31
 */
class UndoRedoStackTest {

    private UndoRedoStack stack;

    @BeforeEach
    void setUp() {
        stack = new UndoRedoStack(3);
    }

    @Test
    void 初始状态_undo和redo栈均为空() {
        assertThat(stack.undoDepth()).isZero();
        assertThat(stack.redoDepth()).isZero();
        assertThat(stack.popUndo()).isEmpty();
        assertThat(stack.popRedo()).isEmpty();
    }

    @Test
    void pushUndo_应正确压栈() {
        var snapshot = createSnapshot("test");
        stack.pushUndo(snapshot);

        assertThat(stack.undoDepth()).isEqualTo(1);
        assertThat(stack.popUndo()).isPresent().hasValue(snapshot);
        assertThat(stack.undoDepth()).isZero();
    }

    @Test
    void pushUndo_应清空redo栈() {
        // 先 push 一些数据到 undo，然后 pop + pushRedo 模拟 undo 操作
        stack.pushUndo(createSnapshot("v1"));
        var undone = stack.popUndo();
        assertThat(undone).isPresent();
        stack.pushRedo(undone.get());
        assertThat(stack.redoDepth()).isEqualTo(1);

        // 新的 pushUndo 应该清空 redo 栈
        stack.pushUndo(createSnapshot("v2"));
        assertThat(stack.redoDepth()).isZero();
    }

    @Test
    void pushUndo_超过maxDepth时应移除最早的快照() {
        // maxDepth = 3
        var v1 = createSnapshot("v1");
        var v2 = createSnapshot("v2");
        var v3 = createSnapshot("v3");
        var v4 = createSnapshot("v4");

        stack.pushUndo(v1);
        stack.pushUndo(v2);
        stack.pushUndo(v3);
        assertThat(stack.undoDepth()).isEqualTo(3);

        // 第 4 个应移除 v1
        stack.pushUndo(v4);
        assertThat(stack.undoDepth()).isEqualTo(3);

        // 弹出顺序应为 v4, v3, v2（v1 已被移除）
        assertThat(stack.popUndo()).hasValueSatisfying(s ->
                assertThat(new String(s.content())).isEqualTo("v4"));
        assertThat(stack.popUndo()).hasValueSatisfying(s ->
                assertThat(new String(s.content())).isEqualTo("v3"));
        assertThat(stack.popUndo()).hasValueSatisfying(s ->
                assertThat(new String(s.content())).isEqualTo("v2"));
        assertThat(stack.popUndo()).isEmpty();
    }

    @Test
    void pushRedo_和popRedo_应正确工作() {
        var snapshot = createSnapshot("redo-v1");
        stack.pushRedo(snapshot);

        assertThat(stack.redoDepth()).isEqualTo(1);
        assertThat(stack.popRedo()).isPresent().hasValue(snapshot);
        assertThat(stack.redoDepth()).isZero();
    }

    @Test
    void maxDepth小于1_应抛出异常() {
        assertThatThrownBy(() -> new UndoRedoStack(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth 必须 >= 1");
    }

    @Test
    void 多次pushUndo和popUndo_应按LIFO顺序() {
        stack.pushUndo(createSnapshot("a"));
        stack.pushUndo(createSnapshot("b"));
        stack.pushUndo(createSnapshot("c"));

        assertThat(stack.popUndo()).hasValueSatisfying(s ->
                assertThat(new String(s.content())).isEqualTo("c"));
        assertThat(stack.popUndo()).hasValueSatisfying(s ->
                assertThat(new String(s.content())).isEqualTo("b"));
        assertThat(stack.popUndo()).hasValueSatisfying(s ->
                assertThat(new String(s.content())).isEqualTo("a"));
    }

    private FileSnapshot createSnapshot(String content) {
        return new FileSnapshot(
                Path.of("/test/file.txt"),
                content.getBytes(),
                Instant.now()
        );
    }
}
