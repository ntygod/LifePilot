package com.lifepilot.meta.infra.file.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileEditHistory} 单元测试。
 *
 * @author zsg
 * @since 2026-03-31
 */
class FileEditHistoryTest {

    @TempDir
    Path tempDir;

    private FileEditHistory history;

    @BeforeEach
    void setUp() {
        history = new FileEditHistory(50, 5 * 1024 * 1024);
    }

    @Test
    void 捕获快照并撤销_应恢复到之前的内容() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "原始内容");

        // 捕获修改前快照
        history.captureBeforeModify(file);

        // 模拟修改
        Files.writeString(file, "修改后内容");

        // 撤销
        var result = history.undo(file);
        assertThat(result).isPresent();
        assertThat(Files.readString(file)).isEqualTo("原始内容");
    }

    @Test
    void 撤销后重做_应恢复修改后的内容() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "v1");

        history.captureBeforeModify(file);
        Files.writeString(file, "v2");

        // 撤销 → 回到 v1
        history.undo(file);
        assertThat(Files.readString(file)).isEqualTo("v1");

        // 重做 → 回到 v2
        var result = history.redo(file);
        assertThat(result).isPresent();
        assertThat(Files.readString(file)).isEqualTo("v2");
    }

    @Test
    void 多次编辑后多次撤销_应按顺序恢复() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "v1");

        // 第一次修改
        history.captureBeforeModify(file);
        Files.writeString(file, "v2");

        // 第二次修改
        history.captureBeforeModify(file);
        Files.writeString(file, "v3");

        // 撤销一次 → v2
        history.undo(file);
        assertThat(Files.readString(file)).isEqualTo("v2");

        // 再撤销一次 → v1
        history.undo(file);
        assertThat(Files.readString(file)).isEqualTo("v1");

        // undo 栈空，返回 empty
        assertThat(history.undo(file)).isEmpty();
    }

    @Test
    void diff_应返回会话起始到当前的差异() throws IOException {
        Path file = tempDir.resolve("hello.py");
        Files.writeString(file, "print('hello')\n");

        history.captureBeforeModify(file);
        Files.writeString(file, "print('world')\n");

        String diff = history.diff(file);
        assertThat(diff).contains("---").contains("+++");
        assertThat(diff).contains("-print('hello')");
        assertThat(diff).contains("+print('world')");
    }

    @Test
    void diff_未修改文件_应返回空() throws IOException {
        Path file = tempDir.resolve("unmodified.txt");
        Files.writeString(file, "不变的内容");

        // 没有调用 captureBeforeModify，所以 diff 应该返回空
        String diff = history.diff(file);
        assertThat(diff).isEmpty();
    }

    @Test
    void diff_内容相同时_应返回空() throws IOException {
        Path file = tempDir.resolve("same.txt");
        Files.writeString(file, "内容不变\n");

        history.captureBeforeModify(file);
        // 写回相同内容
        Files.writeString(file, "内容不变\n");

        String diff = history.diff(file);
        assertThat(diff).isEmpty();
    }

    @Test
    void diffAll_应返回所有修改文件的差异() throws IOException {
        Path file1 = tempDir.resolve("a.txt");
        Path file2 = tempDir.resolve("b.txt");
        Files.writeString(file1, "aaa\n");
        Files.writeString(file2, "bbb\n");

        history.captureBeforeModify(file1);
        history.captureBeforeModify(file2);
        Files.writeString(file1, "AAA\n");
        Files.writeString(file2, "BBB\n");

        String diff = history.diffAll();
        assertThat(diff).contains("-aaa").contains("+AAA");
        assertThat(diff).contains("-bbb").contains("+BBB");
    }

    @Test
    void 超过大小限制的文件_应跳过快照捕获() throws IOException {
        // 使用非常小的限制来测试
        var smallHistory = new FileEditHistory(50, 10); // 最大 10 字节

        Path file = tempDir.resolve("large.txt");
        Files.writeString(file, "这是一个超过限制的文件内容，超过10字节");

        smallHistory.captureBeforeModify(file);

        // 应该无法撤销，因为快照被跳过
        assertThat(smallHistory.undo(file)).isEmpty();
    }

    @Test
    void 不存在的文件_captureBeforeModify应静默跳过() {
        Path nonExistent = tempDir.resolve("non-existent.txt");
        // 不应抛出异常
        history.captureBeforeModify(nonExistent);
        assertThat(history.undoDepth(nonExistent)).isZero();
    }

    @Test
    void undoDepth和redoDepth_应正确反映栈深度() throws IOException {
        Path file = tempDir.resolve("depth.txt");
        Files.writeString(file, "v1");

        assertThat(history.undoDepth(file)).isZero();
        assertThat(history.redoDepth(file)).isZero();

        history.captureBeforeModify(file);
        Files.writeString(file, "v2");
        assertThat(history.undoDepth(file)).isEqualTo(1);

        history.captureBeforeModify(file);
        Files.writeString(file, "v3");
        assertThat(history.undoDepth(file)).isEqualTo(2);

        history.undo(file);
        assertThat(history.undoDepth(file)).isEqualTo(1);
        assertThat(history.redoDepth(file)).isEqualTo(1);
    }

    @Test
    void 会话起始快照_应只在首次修改时捕获() throws IOException {
        Path file = tempDir.resolve("session.txt");
        Files.writeString(file, "初始版本");

        // 第一次修改
        history.captureBeforeModify(file);
        Files.writeString(file, "v2");

        // 第二次修改
        history.captureBeforeModify(file);
        Files.writeString(file, "v3");

        // diff 应该对比 "初始版本" 和 "v3"
        String diff = history.diff(file);
        assertThat(diff).contains("-初始版本").contains("+v3");
    }
}
