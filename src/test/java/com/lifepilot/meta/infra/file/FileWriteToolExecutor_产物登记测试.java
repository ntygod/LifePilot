package com.lifepilot.meta.infra.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.artifact.ArtifactFilterConfig;
import com.lifepilot.tool.model.ArtifactKind;
import com.lifepilot.tool.model.ToolArtifact;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileWriteToolExecutor} 的 ToolArtifact 登记行为测试。
 *
 * <p>覆盖：成功登记、workspace 越界丢弃、过滤命中不登记、配置阈值生效。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
class FileWriteToolExecutor_产物登记测试 {

    @TempDir
    Path workspaceRoot;

    private MetaProperties properties;
    private PathSecurityChecker securityChecker;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        properties.getInfra().getFile().setAllowedDirectories(
                List.of(workspaceRoot.toAbsolutePath().toString()));
        securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
    }

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput("file.write", params, JsonSchema.empty(), null, null);
    }

    @Test
    @DisplayName("写入成功且路径在 workspace 内 → 登记一个 ToolArtifact")
    void 写入成功登记artifact() throws IOException {
        FileWriteToolExecutor executor = new FileWriteToolExecutor(
                securityChecker, null, null, null,
                workspaceRoot, ArtifactFilterConfig.defaultConfig());
        Path target = workspaceRoot.resolve("report.docx");

        ToolResult result = executor.execute(input(
                Map.of("path", target.toString(), "content", "hello")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.artifacts()).hasSize(1);
        ToolArtifact artifact = result.artifacts().get(0);
        assertThat(artifact.fileName()).isEqualTo("report.docx");
        assertThat(artifact.path()).isEqualTo(target.toAbsolutePath().normalize().toString());
        assertThat(artifact.size()).isEqualTo(5);
        // .docx 扩展名映射到 wordprocessingml mime；JDK 的 URLConnection.guessContentTypeFromName
        // 对 .docx 默认不识别，会回落到 application/octet-stream，kind 仍为 FILE
        assertThat(artifact.kind()).isEqualTo(ArtifactKind.FILE);
    }

    @Test
    @DisplayName("写入图片 → kind 推断为 IMAGE")
    void 图片登记为IMAGE_kind() throws IOException {
        FileWriteToolExecutor executor = new FileWriteToolExecutor(
                securityChecker, null, null, null,
                workspaceRoot, ArtifactFilterConfig.defaultConfig());
        Path target = workspaceRoot.resolve("chart.png");

        ToolResult result = executor.execute(input(
                Map.of("path", target.toString(), "content", "fakepng")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).kind()).isEqualTo(ArtifactKind.IMAGE);
        assertThat(result.artifacts().get(0).mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("写入路径越界 → 不登记 artifact，但 write 成功")
    void 写入路径越界_不登记(@TempDir Path outsideRoot) throws IOException {
        // 把 outsideRoot 也加到白名单，否则 PathSecurityChecker 会拒绝
        properties.getInfra().getFile().setAllowedDirectories(
                List.of(workspaceRoot.toAbsolutePath().toString(),
                        outsideRoot.toAbsolutePath().toString()));
        securityChecker = new PathSecurityChecker(properties.getInfra().getFile());

        FileWriteToolExecutor executor = new FileWriteToolExecutor(
                securityChecker, null, null, null,
                workspaceRoot, ArtifactFilterConfig.defaultConfig());
        // 写到 workspaceRoot 之外的另一个路径
        Path target = outsideRoot.resolve("escape.txt");

        ToolResult result = executor.execute(input(
                Map.of("path", target.toString(), "content", "x")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.artifacts()).isEmpty();
        assertThat(Files.readString(target)).isEqualTo("x");
    }

    @Test
    @DisplayName("workspaceRoot 为 null（旧 4 参构造器）→ 不登记 artifact，向后兼容")
    void 旧构造器关闭artifact登记() throws IOException {
        FileWriteToolExecutor executor = new FileWriteToolExecutor(
                securityChecker, null, null, null);  // 旧 4 参构造器
        Path target = workspaceRoot.resolve("legacy.txt");

        ToolResult result = executor.execute(input(
                Map.of("path", target.toString(), "content", "x")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    @DisplayName("过滤命中（如 .tmp）→ 不登记 artifact")
    void 过滤命中不登记() throws IOException {
        FileWriteToolExecutor executor = new FileWriteToolExecutor(
                securityChecker, null, null, null,
                workspaceRoot, ArtifactFilterConfig.defaultConfig());
        Path target = workspaceRoot.resolve("scratch.tmp");

        ToolResult result = executor.execute(input(
                Map.of("path", target.toString(), "content", "x")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    @DisplayName("size 超过 maxSizeMb → 不登记 artifact（边界）")
    void size超限不登记() throws IOException {
        // 1MB 上限的 filter
        ArtifactFilterConfig tinyConfig = new ArtifactFilterConfig(1, 20, null, null);
        FileWriteToolExecutor executor = new FileWriteToolExecutor(
                securityChecker, null, null, null,
                workspaceRoot, tinyConfig);
        Path target = workspaceRoot.resolve("big.bin");
        // 写入 1.5MB 内容
        byte[] big = new byte[(int) (1.5 * 1024 * 1024)];
        java.util.Arrays.fill(big, (byte) 'a');

        ToolResult result = executor.execute(input(
                Map.of("path", target.toString(), "content", new String(big))));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.artifacts()).isEmpty();
    }
}
