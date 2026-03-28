package com.lifepilot.meta.infra.shell;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShellExecToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class ShellExecToolExecutorTest {

    private ShellExecToolExecutor executor;
    private MetaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        executor = new ShellExecToolExecutor(properties, null);
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
        executor = new ShellExecToolExecutor(properties, null);

        // 生成超过 10 字符的输出
        ToolInput input = buildInput(Map.of("command", "echo abcdefghijklmnopqrstuvwxyz"));

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        String stdout = (String) result.data().get("stdout");
        assertThat(stdout).contains("输出已截断");
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
        ToolInput input = buildInput(Map.of(
                "command", "echo test",
                "workingDirectory", "/nonexistent/path/that/does/not/exist"
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
}
