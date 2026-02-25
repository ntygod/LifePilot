package com.lifepilot.skill.action;

import com.lifepilot.skill.config.SkillConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShellActionExecutor} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class ShellActionExecutorTest {

    private DangerousCommandDetector dangerousCommandDetector;
    private VariableResolver variableResolver;
    private SkillConfigProperties config;
    private ShellActionExecutor executor;

    @BeforeEach
    void setUp() {
        dangerousCommandDetector = new DangerousCommandDetector();
        variableResolver = new VariableResolver();
        config = new SkillConfigProperties();
        executor = new ShellActionExecutor(dangerousCommandDetector, variableResolver, config);
    }

    // ─────────────────────────────────────────────
    //  execute — 正常执行
    // ─────────────────────────────────────────────

    @Test
    void execute_正常命令返回成功结果() {
        var action = new SkillAction.ShellAction("echo hello", 10);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("exitCode=0");
        assertThat(result.output()).contains("hello");
        assertThat(result.data()).isNotNull();
        assertThat(result.data().get("exitCode")).isEqualTo(0);
        assertThat(result.data().get("stdout").toString()).contains("hello");
    }

    @Test
    void execute_结果包含exitCode和stdout和stderr() {
        var action = new SkillAction.ShellAction("echo output-text", 10);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKeys("exitCode", "stdout", "stderr");
    }

    // ─────────────────────────────────────────────
    //  execute — 变量替换
    // ─────────────────────────────────────────────

    @Test
    void execute_替换命令中的变量() {
        var action = new SkillAction.ShellAction("echo ${params.msg}", 10);

        ActionResult result = executor.execute(action, Map.of("msg", "world"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("world");
    }

    // ─────────────────────────────────────────────
    //  execute — 危险命令拦截
    // ─────────────────────────────────────────────

    @Test
    void execute_危险命令rm_rf被拦截() {
        var action = new SkillAction.ShellAction("rm -rf /", 10);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("危险命令拦截");
    }

    @Test
    void execute_危险命令sudo被拦截() {
        var action = new SkillAction.ShellAction("sudo apt install vim", 10);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("危险命令拦截");
    }

    @Test
    void execute_危险命令shutdown被拦截() {
        var action = new SkillAction.ShellAction("shutdown -h now", 10);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("危险命令拦截");
    }

    // ─────────────────────────────────────────────
    //  execute — Shell 注入检测
    // ─────────────────────────────────────────────

    @Test
    void execute_参数包含分号被拦截() {
        var action = new SkillAction.ShellAction("echo ${params.input}", 10);

        ActionResult result = executor.execute(action, Map.of("input", "hello; rm -rf /"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("Shell 注入检测");
    }

    @Test
    void execute_参数包含管道符被拦截() {
        var action = new SkillAction.ShellAction("echo ${params.input}", 10);

        ActionResult result = executor.execute(action, Map.of("input", "hello | cat /etc/passwd"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("Shell 注入检测");
    }

    @Test
    void execute_参数包含反引号被拦截() {
        var action = new SkillAction.ShellAction("echo ${params.input}", 10);

        ActionResult result = executor.execute(action, Map.of("input", "`whoami`"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("Shell 注入检测");
    }

    // ─────────────────────────────────────────────
    //  execute — 非零退出码
    // ─────────────────────────────────────────────

    @Test
    void execute_非零退出码返回错误结果() {
        // exit 1 在 Windows 和 Unix 上都能工作
        var action = new SkillAction.ShellAction("exit 1", 10);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("exitCode=1");
    }

    // ─────────────────────────────────────────────
    //  execute — 超时控制
    // ─────────────────────────────────────────────

    @Test
    void execute_超时强制终止进程() {
        // 使用 ping 命令模拟长时间运行（Windows 和 Unix 兼容）
        String os = System.getProperty("os.name", "").toLowerCase();
        String command = os.contains("win")
                ? "ping -n 100 127.0.0.1"
                : "sleep 100";
        var action = new SkillAction.ShellAction(command, 1);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("超时");
    }

    @Test
    void execute_实际超时取action和config的最小值() {
        // config 默认 maxTimeoutSeconds=30，action 设置 5
        // 实际超时应为 min(5, 30) = 5
        config.getShellAction().setMaxTimeoutSeconds(3);

        String os = System.getProperty("os.name", "").toLowerCase();
        String command = os.contains("win")
                ? "ping -n 100 127.0.0.1"
                : "sleep 100";
        // action 设置 10 秒，但 config 限制为 3 秒
        var action = new SkillAction.ShellAction(command, 10);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("超时");
        // 超时消息应显示实际超时值 3 秒
        assertThat(result.output()).contains("3");
    }

    // ─────────────────────────────────────────────
    //  createProcessBuilder — 平台适配
    // ─────────────────────────────────────────────

    @Test
    void createProcessBuilder_根据操作系统选择Shell() {
        ProcessBuilder pb = executor.createProcessBuilder("echo test");
        var command = pb.command();

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            assertThat(command.get(0)).isEqualTo("cmd");
            assertThat(command.get(1)).isEqualTo("/c");
        } else {
            assertThat(command.get(0)).isEqualTo("sh");
            assertThat(command.get(1)).isEqualTo("-c");
        }
        assertThat(command.get(2)).isEqualTo("echo test");
    }

    // ─────────────────────────────────────────────
    //  execute — 安全参数正常执行
    // ─────────────────────────────────────────────

    @Test
    void execute_安全参数正常替换并执行() {
        var action = new SkillAction.ShellAction("echo ${params.name}", 10);

        ActionResult result = executor.execute(action, Map.of("name", "lifepilot"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("lifepilot");
    }
}
