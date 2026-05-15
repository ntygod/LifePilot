package com.lifepilot.meta.infra.shell;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.guard.CommandGuard;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ShellExecToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class ShellExecToolExecutorTest {

    private ShellExecToolExecutor executor;
    private MetaProperties properties;
    private BackgroundProcessManager backgroundProcessManager;
    private final WorkspaceResolver workspaceResolver;

    {
        var zhiweiPaths = mock(ZhiweiPaths.class);
        when(zhiweiPaths.workspace()).thenReturn(
                Path.of(System.getProperty("user.home"), "zhiwei", "workspace"));
        workspaceResolver = new WorkspaceResolver(null, zhiweiPaths);
    }

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        executor = new ShellExecToolExecutor(properties, null, workspaceResolver, null);
        backgroundProcessManager = null;
    }

    @AfterEach
    void tearDown() {
        if (backgroundProcessManager != null) {
            backgroundProcessManager.shutdown();
        }
    }

    // ─────────────────────────────────────────────
    //  黑名单拒绝测试
    // ─────────────────────────────────────────────

    @Test
    void execute_黑名单命令被拒绝_rmRf() {
        ToolInput input = buildInput(Map.of("command", "rm -rf /"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("安全策略拒绝");
    }

    @Test
    void execute_黑名单命令被拒绝_shutdown() {
        ToolInput input = buildInput(Map.of("command", "shutdown -h now"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("安全策略拒绝");
    }

    @Test
    void execute_黑名单命令被拒绝_reboot() {
        ToolInput input = buildInput(Map.of("command", "reboot"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("安全策略拒绝");
    }

    @Test
    void execute_黑名单命令被拒绝_formatDrive() {
        ToolInput input = buildInput(Map.of("command", "format C:"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("安全策略拒绝");
    }

    @Test
    void execute_黑名单命令被拒绝_ddIf() {
        ToolInput input = buildInput(Map.of("command", "dd if=/dev/zero of=/dev/sda"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("安全策略拒绝");
    }

    // ─────────────────────────────────────────────
    //  CommandGuard 接入测试 — shell.exec 不能绕过 code.execute 的护栏
    // ─────────────────────────────────────────────

    @Test
    void execute_HARDLINE命令被护栏永久阻断() {
        // 注入真实 CommandGuard，验证 shell.exec 走 HARDLINE 阻断（与 code.execute 同款语义）
        var guard = new CommandGuard(new SandboxConfigProperties());
        var executorWithGuard = new ShellExecToolExecutor(properties, null, workspaceResolver, guard);
        // 选 systemctl poweroff：HARDLINE 命中（关机），不在 ShellExec blacklist 内，能走到 guard 层
        ToolInput input = buildInput(Map.of("command", "systemctl poweroff"));

        ToolResult result = executorWithGuard.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("永久阻断");
    }

    @Test
    void execute_DANGEROUS命令被护栏拒绝() {
        var guard = new CommandGuard(new SandboxConfigProperties());
        var executorWithGuard = new ShellExecToolExecutor(properties, null, workspaceResolver, guard);
        // git reset --hard 是 DANGEROUS（默认拒绝）
        ToolInput input = buildInput(Map.of("command", "git reset --hard HEAD~5"));

        ToolResult result = executorWithGuard.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("危险操作");
    }

    // ─────────────────────────────────────────────
    //  成功执行测试
    // ─────────────────────────────────────────────

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void execute_成功执行echo命令_Unix() {
        ToolInput input = buildInput(Map.of("command", "echo hello"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((String) result.data().get("stdout")).contains("hello");
        assertThat((int) result.data().get("exitCode")).isZero();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_成功执行echo命令_Windows() {
        ToolInput input = buildInput(Map.of("command", "echo hello"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((String) result.data().get("stdout")).contains("hello");
        assertThat((int) result.data().get("exitCode")).isZero();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_成功读取中文输出_Windows() {
        ToolInput input = buildInput(Map.of("command", "Write-Output '中文输出'"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((String) result.data().get("stdout")).contains("中文输出");
        assertThat((int) result.data().get("exitCode")).isZero();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void execute_捕获stderr_Unix() {
        ToolInput input = buildInput(Map.of("command", "echo error_msg >&2"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((String) result.data().get("stderr")).contains("error_msg");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_捕获stderr_Windows() {
        ToolInput input = buildInput(Map.of("command", "[Console]::Error.WriteLine('error_msg')"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((String) result.data().get("stderr")).contains("error_msg");
    }

    // ─────────────────────────────────────────────
    //  超时测试
    // ─────────────────────────────────────────────

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void execute_超时命令被终止_Unix() {
        ToolInput input = buildInput(Map.of(
                "command", "sleep 60",
                "timeoutSeconds", 1
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("超时");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void execute_超时命令被终止_Windows() {
        ToolInput input = buildInput(Map.of(
                "command", "Start-Sleep -Seconds 60",
                "timeoutSeconds", 1
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("超时");
    }

    // ─────────────────────────────────────────────
    //  输出截断测试
    // ─────────────────────────────────────────────

    @Test
    void truncateOutput_短输出不截断() {
        String output = "short output";
        String result = executor.truncateOutput(output, 100);

        assertThat(result).isEqualTo(output);
    }

    @Test
    void truncateOutput_超长输出被截断() {
        String output = "a".repeat(200);
        String result = executor.truncateOutput(output, 50);

        assertThat(result).startsWith("a".repeat(50));
        assertThat(result).contains("输出已截断");
        assertThat(result).contains("200 字符");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void execute_输出超过maxOutputLength被截断_Unix() {
        // 设置极小的 maxOutputLength
        properties.getInfra().getShell().setMaxOutputLength(10);
        executor = new ShellExecToolExecutor(properties, null, workspaceResolver, null);

        // 生成超过 10 字符的输出
        ToolInput input = buildInput(Map.of("command", "echo abcdefghijklmnopqrstuvwxyz"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        String stdout = (String) result.data().get("stdout");
        assertThat(stdout).contains("输出已截断");
    }

    @Test
    void execute_yieldMs快速失败时保留真实exitCode与双通道输出() {
        backgroundProcessManager = new BackgroundProcessManager(properties.getInfra().getProcess(), null);
        executor = new ShellExecToolExecutor(properties, backgroundProcessManager, workspaceResolver, null);

        ToolInput input = buildInput(Map.of(
                "command", buildStdoutStderrFailCommand(7),
                "yieldMs", 3000
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("exitCode=7");
        assertThat((int) result.data().get("exitCode")).isEqualTo(7);
        assertThat((String) result.data().get("stdout")).contains("yield-stdout");
        assertThat((String) result.data().get("stderr")).contains("yield-stderr");
        assertThat((String) result.data().get("output")).contains("yield-stdout").contains("yield-stderr");
    }

    // ─────────────────────────────────────────────
    //  参数缺失测试
    // ─────────────────────────────────────────────

    @Test
    void execute_缺少command参数返回错误() {
        ToolInput input = buildInput(Map.of());

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("command");
    }

    // ─────────────────────────────────────────────
    //  工作目录测试
    // ─────────────────────────────────────────────

    @Test
    void execute_不存在的工作目录返回错误() {
        // 用 tmp 下的唯一随机子目录，保证跨平台都是"绝对路径 + 不存在"（normalize 不回退）
        Path nonexistent = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("zhiwei-test-nonexistent-" + UUID.randomUUID())
                .toAbsolutePath();
        ToolInput input = buildInput(Map.of(
                "command", "echo test",
                "workingDirectory", nonexistent.toString()
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("工作目录不存在");
    }

    // ─────────────────────────────────────────────
    //  黑名单检查方法直接测试
    // ─────────────────────────────────────────────

    @Test
    void checkBlacklist_安全命令通过() {
        ToolResult result = executor.checkBlacklist("ls -la");

        assertThat(result).isNull();
    }

    @Test
    void checkBlacklist_危险命令被拒绝() {
        ToolResult result = executor.checkBlacklist("rm -rf /");

        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
    }

    @Test
    void checkBlacklist_mkfs命令被拒绝() {
        ToolResult result = executor.checkBlacklist("mkfs.ext4 /dev/sda1");

        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
    }

    @Test
    void checkBlacklist_forkBomb被拒绝() {
        ToolResult result = executor.checkBlacklist(":(){ :|:& };:");

        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
    }

    @Test
    void checkBlacklist_chmod777根目录被拒绝() {
        ToolResult result = executor.checkBlacklist("chmod -R 777 /");

        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
    }

    // ─────────────────────────────────────────────
    //  黑名单精确匹配 — 不误杀合法命令
    // ─────────────────────────────────────────────

    @Test
    void checkBlacklist_rmRf子目录不被拒绝() {
        // rm -rf /tmp/build 是合法操作，不应被拒绝
        ToolResult result = executor.checkBlacklist("rm -rf /tmp/build");

        assertThat(result).isNull();
    }

    @Test
    void checkBlacklist_包含shutdown子串的命令不被拒绝() {
        // 包含 shutdown 子串但不是 shutdown 命令本身
        ToolResult result = executor.checkBlacklist("cat /var/log/shutdown.log");

        assertThat(result).isNull();
    }

    @Test
    void checkBlacklist_管道中的shutdown被拒绝() {
        ToolResult result = executor.checkBlacklist("echo done | shutdown -h now");

        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
    }

    @Test
    void checkBlacklist_分号后的reboot被拒绝() {
        ToolResult result = executor.checkBlacklist("echo done; reboot");

        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private ToolInput buildInput(Map<String, Object> params) {
        return new ToolInput("shell.exec", params, JsonSchema.empty(), null, null);
    }

    private String buildStdoutStderrFailCommand(int exitCode) {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return "Write-Output 'yield-stdout'; [Console]::Error.WriteLine('yield-stderr'); exit " + exitCode;
        }
        return "printf 'yield-stdout\\n'; printf 'yield-stderr\\n' >&2; exit " + exitCode;
    }
}
