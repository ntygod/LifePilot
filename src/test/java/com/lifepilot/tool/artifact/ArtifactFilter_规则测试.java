package com.lifepilot.tool.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArtifactFilter#accept} 与 {@link ArtifactFilter#isInWorkspaceRoot} 行为测试。
 *
 * <p>覆盖 size 上下限、隐藏文件、扩展名排除、目录排除、workspace 白名单边界
 * （含 {@code ..} 路径攻击防护）。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
class ArtifactFilter_规则测试 {

    @Test
    @DisplayName("size <= 0 拒绝")
    void size_零或负_拒绝(@TempDir Path tmp) {
        Path p = tmp.resolve("x.txt");
        ArtifactFilterConfig config = ArtifactFilterConfig.defaultConfig();
        assertThat(ArtifactFilter.accept(p, 0, config)).isFalse();
        assertThat(ArtifactFilter.accept(p, -1, config)).isFalse();
    }

    @Test
    @DisplayName("size 超过 maxSizeMb 拒绝")
    void size_超过上限_拒绝(@TempDir Path tmp) {
        Path p = tmp.resolve("big.bin");
        ArtifactFilterConfig config = new ArtifactFilterConfig(1, 20, null, null);  // 1MB 上限
        long oneMb = 1024L * 1024L;
        assertThat(ArtifactFilter.accept(p, oneMb, config)).isTrue();      // 边界等于
        assertThat(ArtifactFilter.accept(p, oneMb + 1, config)).isFalse(); // 超过
    }

    @Test
    @DisplayName("隐藏文件（. 开头）拒绝")
    void 隐藏文件拒绝(@TempDir Path tmp) {
        Path hidden = tmp.resolve(".secret");
        assertThat(ArtifactFilter.accept(hidden, 100, ArtifactFilterConfig.defaultConfig())).isFalse();
    }

    @Test
    @DisplayName("扩展名命中 excludedExtensions 拒绝")
    void 扩展名排除命中拒绝(@TempDir Path tmp) {
        ArtifactFilterConfig config = ArtifactFilterConfig.defaultConfig();
        assertThat(ArtifactFilter.accept(tmp.resolve("a.tmp"), 100, config)).isFalse();
        assertThat(ArtifactFilter.accept(tmp.resolve("a.log"), 100, config)).isFalse();
        assertThat(ArtifactFilter.accept(tmp.resolve("a.swp"), 100, config)).isFalse();
        // 大小写不敏感
        assertThat(ArtifactFilter.accept(tmp.resolve("a.LOG"), 100, config)).isFalse();
    }

    @Test
    @DisplayName("路径任意一段命中 excludedDirs 拒绝")
    void 路径段排除命中拒绝(@TempDir Path tmp) {
        ArtifactFilterConfig config = ArtifactFilterConfig.defaultConfig();
        assertThat(ArtifactFilter.accept(
                tmp.resolve(".git").resolve("HEAD"), 100, config)).isFalse();
        assertThat(ArtifactFilter.accept(
                tmp.resolve("project").resolve("node_modules").resolve("a.js"), 100, config)).isFalse();
        assertThat(ArtifactFilter.accept(
                tmp.resolve("__pycache__").resolve("x.pyc"), 100, config)).isFalse();
        assertThat(ArtifactFilter.accept(
                tmp.resolve("target").resolve("classes").resolve("a.class"), 100, config)).isFalse();
    }

    @Test
    @DisplayName("正常文件通过过滤")
    void 正常文件通过(@TempDir Path tmp) {
        Path p = tmp.resolve("report.docx");
        assertThat(ArtifactFilter.accept(p, 1024, ArtifactFilterConfig.defaultConfig())).isTrue();
    }

    @Test
    @DisplayName("isInWorkspaceRoot：候选路径在 workspace 之下")
    void workspace_白名单_命中(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        Files.createDirectories(workspace);
        Path candidate = workspace.resolve("sess-1").resolve("report.docx");
        assertThat(ArtifactFilter.isInWorkspaceRoot(candidate, workspace)).isTrue();
    }

    @Test
    @DisplayName("isInWorkspaceRoot：候选路径在 workspace 之外")
    void workspace_白名单_越界(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        Files.createDirectories(workspace);
        Path outside = tmp.resolve("other").resolve("evil.docx");
        assertThat(ArtifactFilter.isInWorkspaceRoot(outside, workspace)).isFalse();
    }

    @Test
    @DisplayName("isInWorkspaceRoot：.. 路径攻击被识别")
    void workspace_白名单_dotdot攻击防护(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        Files.createDirectories(workspace);
        // 候选路径用 .. 试图跳出 workspace，normalize 后会被识别为外部路径
        Path attack = workspace.resolve("..").resolve("etc").resolve("passwd");
        assertThat(ArtifactFilter.isInWorkspaceRoot(attack, workspace)).isFalse();
    }

    @Test
    @DisplayName("isInWorkspaceRoot：相似前缀但非子目录拒绝")
    void workspace_白名单_相似前缀拒绝(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        Files.createDirectories(workspace);
        // workspace_evil 与 workspace 共享字符串前缀但不是子目录
        Path similar = tmp.resolve("workspace_evil").resolve("a.docx");
        assertThat(ArtifactFilter.isInWorkspaceRoot(similar, workspace)).isFalse();
    }

    @Test
    @DisplayName("null 参数防御性返回 false")
    void null参数返回false() {
        Path workspace = Path.of("/tmp/workspace");
        assertThat(ArtifactFilter.isInWorkspaceRoot(null, workspace)).isFalse();
        assertThat(ArtifactFilter.isInWorkspaceRoot(workspace, null)).isFalse();
        assertThat(ArtifactFilter.accept(null, 1, ArtifactFilterConfig.defaultConfig())).isFalse();
        assertThat(ArtifactFilter.accept(workspace.resolve("x.txt"), 1, null)).isFalse();
    }

    @Test
    @DisplayName("isInWorkspaceRoot：符号链接指向 workspace 外部时拒绝")
    void workspace_白名单_符号链接拒绝(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        Files.createDirectories(workspace);
        // 在 workspace 外创建一个敏感文件
        Path external = tmp.resolve("secret.txt");
        Files.writeString(external, "sensitive data");
        // 在 workspace 内创建指向外部文件的符号链接
        Path symlink = workspace.resolve("link.txt");
        try {
            Files.createSymbolicLink(symlink, external);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 非管理员可能无法创建符号链接，跳过测试
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "符号链接不可用: " + e.getMessage());
            return;
        }
        // 符号链接应被拒绝
        assertThat(ArtifactFilter.isInWorkspaceRoot(symlink, workspace)).isFalse();
    }
}
