package com.lifepilot.meta.infra.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.artifact.ArtifactFilterConfig;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ShellExecToolExecutor} 的产物 diff 探测行为测试。
 *
 * <p>覆盖：默认 cwd diff 识别新增文件 / expectedOutputs 显式声明 / cwd 越界跳过 /
 * 数量截断 / 命令失败仍登记。Windows / Unix 命令分支用 {@code @EnabledOnOs} 隔离。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
class ShellExecToolExecutor_diff_测试 {

    @TempDir
    Path workspace;

    private ShellExecToolExecutor executor;
    private MetaProperties properties;
    private WorkspaceResolver workspaceResolver;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        var zhiweiPaths = mock(ZhiweiPaths.class);
        when(zhiweiPaths.workspace()).thenReturn(workspace);
        workspaceResolver = new WorkspaceResolver(null, zhiweiPaths);
        executor = new ShellExecToolExecutor(properties, null, workspaceResolver, null, null);
        executor.setArtifactFilterConfig(ArtifactFilterConfig.defaultConfig());
    }

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput("shell.exec", params, JsonSchema.empty(), null, null);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows 默认 cwd diff：echo 新建文件 → 出现在 artifacts")
    void windows_默认diff识别新增文件() throws IOException {
        ToolResult result = executor.execute(input(Map.of(
                "command", "Set-Content -Path output.docx -Value 'hello'",
                "workingDirectory", workspace.toString()
        )));

        assertThat(result.isSuccess()).as(result.error()).isTrue();
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).fileName()).isEqualTo("output.docx");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("Unix 默认 cwd diff：echo 新建文件 → 出现在 artifacts")
    void unix_默认diff识别新增文件() throws IOException {
        ToolResult result = executor.execute(input(Map.of(
                "command", "echo hello > output.docx",
                "workingDirectory", workspace.toString()
        )));

        assertThat(result.isSuccess()).as(result.error()).isTrue();
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).fileName()).isEqualTo("output.docx");
    }

    @Test
    @DisplayName("expectedOutputs 显式声明：仅检查指定路径")
    void expectedOutputs_仅检查指定路径() throws IOException {
        // 预先创建一个文件，模拟命令"产生"了它
        Path expected = workspace.resolve("declared.docx");
        Files.writeString(expected, "data");

        // 同时创建一个不在 expectedOutputs 里的文件 —— diff 模式下会被识别，
        // expectedOutputs 模式下不会
        Path noise = workspace.resolve("noise.docx");
        Files.writeString(noise, "noise");

        // 用一个不实际产文件的命令测试，验证 expectedOutputs 模式只看声明的文件
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "Write-Output 'no-op'"
                : "true";
        ToolResult result = executor.execute(input(Map.of(
                "command", cmd,
                "workingDirectory", workspace.toString(),
                "expectedOutputs", List.of("declared.docx")
        )));

        assertThat(result.isSuccess()).as(result.error()).isTrue();
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).fileName()).isEqualTo("declared.docx");
    }

    @Test
    @DisplayName("cwd 越界（不在 workspace 白名单）→ 跳过 diff")
    void cwd越界跳过diff(@TempDir Path outsideWorkspace) {
        // executor 的 workspaceResolver 指向 workspace；但传入 outsideWorkspace 作为 cwd
        // workspaceResolver.normalizeWithInfo 因绝对路径不会替换，但 ArtifactFilter
        // 会判定越界并跳过 diff
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "Write-Output 'x'"
                : "echo x";
        ToolResult result = executor.execute(input(Map.of(
                "command", cmd,
                "workingDirectory", outsideWorkspace.toString()
        )));

        assertThat(result.isSuccess()).as(result.error()).isTrue();
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    @DisplayName("artifactFilterConfig 未注入（旧调用点）→ 不登记任何 artifact")
    void filterConfig未注入_关闭登记() {
        var legacyExecutor = new ShellExecToolExecutor(properties, null, workspaceResolver, null, null);
        // 未调 setArtifactFilterConfig
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "Set-Content -Path legacy.txt -Value 'x'"
                : "echo x > legacy.txt";
        ToolResult result = legacyExecutor.execute(input(Map.of(
                "command", cmd,
                "workingDirectory", workspace.toString()
        )));

        assertThat(result.isSuccess()).as(result.error()).isTrue();
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    @DisplayName("数量超过 maxCountPerTool → 截断到上限")
    void 数量超过上限_截断() throws IOException {
        // 把 maxCountPerTool 设小为 2
        executor.setArtifactFilterConfig(new ArtifactFilterConfig(50, 2, null, null));

        // 命令产生 3 个文件
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "Set-Content a.txt 'a'; Set-Content b.txt 'b'; Set-Content c.txt 'c'"
                : "echo a > a.txt; echo b > b.txt; echo c > c.txt";
        ToolResult result = executor.execute(input(Map.of(
                "command", cmd,
                "workingDirectory", workspace.toString()
        )));

        assertThat(result.isSuccess()).as(result.error()).isTrue();
        assertThat(result.artifacts()).hasSize(2);
    }

    @Test
    @DisplayName("命令失败（非零退出）→ 仍登记 diff 产物")
    void 命令失败仍登记diff产物() throws IOException {
        // 先产文件再返回错误码
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "Set-Content output.docx 'data'; exit 1"
                : "echo data > output.docx; exit 1";
        ToolResult result = executor.execute(input(Map.of(
                "command", cmd,
                "workingDirectory", workspace.toString()
        )));

        // exitCode != 0 → ToolResult 失败
        assertThat(result.isSuccess()).isFalse();
        // 但 diff 产物仍然登记了
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).fileName()).isEqualTo("output.docx");
    }

    @Test
    @DisplayName("过滤规则：.tmp 文件不会进入产物")
    void 过滤规则_tmp扩展名排除() {
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "Set-Content scratch.tmp 'x'; Set-Content keep.docx 'y'"
                : "echo x > scratch.tmp; echo y > keep.docx";
        ToolResult result = executor.execute(input(Map.of(
                "command", cmd,
                "workingDirectory", workspace.toString()
        )));

        assertThat(result.isSuccess()).as(result.error()).isTrue();
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).fileName()).isEqualTo("keep.docx");
    }
}
