package com.lifepilot.meta.infra.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.lifepilot.tool.artifact.ArtifactFilterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorkspaceDiffSnapshot} 快照对比行为测试。
 *
 * <p>覆盖：新增文件 / mtime 修改 / size 修改 / 删除文件不报告 / 过滤规则生效 /
 * 非目录 cwd 处理。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
class WorkspaceDiffSnapshot_对比测试 {

    @TempDir
    Path cwd;

    @Test
    @DisplayName("新增文件 → 出现在 diff 中")
    void 新增文件_diff命中() throws IOException {
        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> before = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        Path newFile = cwd.resolve("output.docx");
        Files.writeString(newFile, "hello");

        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> after = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        List<Path> changed = WorkspaceDiffSnapshot.diff(before, after);
        assertThat(changed).containsExactly(newFile.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("文件 mtime 变化 → 出现在 diff 中")
    void mtime变化_diff命中() throws IOException, InterruptedException {
        Path file = cwd.resolve("a.txt");
        Files.writeString(file, "v1");

        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> before = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        // 把 mtime 推进 5 秒，size 不变
        Instant newer = Instant.now().plusSeconds(5);
        Files.setLastModifiedTime(file, FileTime.from(newer));

        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> after = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        List<Path> changed = WorkspaceDiffSnapshot.diff(before, after);
        assertThat(changed).containsExactly(file.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("文件 size 变化 → 出现在 diff 中")
    void size变化_diff命中() throws IOException {
        Path file = cwd.resolve("a.txt");
        Files.writeString(file, "v1");

        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> before = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        Files.writeString(file, "v1 with more bytes");

        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> after = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        List<Path> changed = WorkspaceDiffSnapshot.diff(before, after);
        assertThat(changed).containsExactly(file.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("删除文件 → 不出现在 diff 中")
    void 删除文件_diff不报告() throws IOException {
        Path file = cwd.resolve("a.txt");
        Files.writeString(file, "v1");

        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> before = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        Files.delete(file);

        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> after = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        List<Path> changed = WorkspaceDiffSnapshot.diff(before, after);
        assertThat(changed).isEmpty();
    }

    @Test
    @DisplayName("过滤规则：.git 目录文件不进入快照")
    void 过滤规则_排除目录() throws IOException {
        Path gitDir = cwd.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "ref: ...");
        Path normal = cwd.resolve("a.txt");
        Files.writeString(normal, "data");

        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> snap = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        assertThat(snap).hasSize(1);
        assertThat(snap.keySet()).containsExactly(normal.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("过滤规则：.tmp 文件不进入快照")
    void 过滤规则_排除扩展名() throws IOException {
        Files.writeString(cwd.resolve("scratch.tmp"), "noise");
        Path keep = cwd.resolve("output.docx");
        Files.writeString(keep, "data");

        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> snap = WorkspaceDiffSnapshot.take(
                cwd, ArtifactFilterConfig.defaultConfig());

        assertThat(snap.keySet()).containsExactly(keep.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("非目录 cwd → 返回空 Map（不报错）")
    void 非目录cwd_返回空map(@TempDir Path tmp) {
        Path file = tmp.resolve("not-a-dir.txt");
        Map<Path, WorkspaceDiffSnapshot.FileSnapshot> snap = WorkspaceDiffSnapshot.take(
                file, ArtifactFilterConfig.defaultConfig());
        assertThat(snap).isEmpty();
    }

    @Test
    @DisplayName("空快照对比 → 返回空 list")
    void 空快照对比() {
        List<Path> changed = WorkspaceDiffSnapshot.diff(Map.of(), Map.of());
        assertThat(changed).isEmpty();
    }
}
