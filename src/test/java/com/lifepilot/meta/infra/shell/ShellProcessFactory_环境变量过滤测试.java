package com.lifepilot.meta.infra.shell;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShellProcessFactory} 环境变量黑名单过滤测试。
 *
 * <p>ShellProcessFactory 是 final 工具类（静态方法），
 * 通过检查生成的 ProcessBuilder 的 environment() 来验证过滤逻辑。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
class ShellProcessFactory_环境变量过滤测试 {

    /** 使用系统临时目录作为工作目录，避免路径不存在。 */
    private static final Path WORK_DIR = Path.of(System.getProperty("java.io.tmpdir"));

    // ─────────────────────────────────────────────
    //  env 黑名单过滤
    // ─────────────────────────────────────────────

    @Test
    void createShellProcess_PATH被过滤() {
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                "echo test", WORK_DIR, null, false,
                Map.of("PATH", "/evil/path", "MY_VAR", "safe"));

        // PATH 不应被覆盖为 /evil/path（保留系统原始 PATH 或不设置用户注入的值）
        // MY_VAR 应被注入
        assertThat(pb.environment().get("MY_VAR")).isEqualTo("safe");
        // PATH 应仍是系统原始值，不被覆盖为 /evil/path
        assertThat(pb.environment().get("PATH")).isNotEqualTo("/evil/path");
    }

    @Test
    void createShellProcess_LD_PRELOAD被过滤() {
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                "echo test", WORK_DIR, null, false,
                Map.of("LD_PRELOAD", "/evil/lib.so"));

        // LD_PRELOAD 不应被注入
        assertThat(pb.environment().get("LD_PRELOAD")).isNotEqualTo("/evil/lib.so");
    }

    @Test
    void createShellProcess_LD_LIBRARY_PATH被过滤() {
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                "echo test", WORK_DIR, null, false,
                Map.of("LD_LIBRARY_PATH", "/evil/lib"));

        assertThat(pb.environment().get("LD_LIBRARY_PATH")).isNotEqualTo("/evil/lib");
    }

    @Test
    void createShellProcess_DYLD_INSERT_LIBRARIES被过滤() {
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                "echo test", WORK_DIR, null, false,
                Map.of("DYLD_INSERT_LIBRARIES", "/evil/inject.dylib"));

        assertThat(pb.environment().get("DYLD_INSERT_LIBRARIES")).isNotEqualTo("/evil/inject.dylib");
    }

    @Test
    void createShellProcess_内部命令传递环境变量被过滤() {
        // LIFEPILOT_SHELL_COMMAND 是内部使用的环境变量，不应被外部覆盖
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                "echo real-command", WORK_DIR, null, false,
                Map.of(ShellProcessFactory.WINDOWS_COMMAND_ENV, "injected-command"));

        // Windows 下该变量应保持为 "echo real-command" 而非被注入值覆盖
        // 非 Windows 下该变量可能不存在
        if (ShellProcessFactory.isWindows()) {
            assertThat(pb.environment().get(ShellProcessFactory.WINDOWS_COMMAND_ENV))
                    .isEqualTo("echo real-command");
        }
    }

    // ─────────────────────────────────────────────
    //  正常 env 变量保留
    // ─────────────────────────────────────────────

    @Test
    void createShellProcess_正常环境变量被保留() {
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                "echo test", WORK_DIR, null, false,
                Map.of("MY_VAR", "hello", "ANOTHER_VAR", "world"));

        assertThat(pb.environment().get("MY_VAR")).isEqualTo("hello");
        assertThat(pb.environment().get("ANOTHER_VAR")).isEqualTo("world");
    }

    @Test
    void createShellProcess_env为null时不报错() {
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                "echo test", WORK_DIR, null, false, null);

        // 应正常创建，不抛异常
        assertThat(pb).isNotNull();
        assertThat(pb.directory().toPath()).isEqualTo(WORK_DIR);
    }

    @Test
    void createShellProcess_env为空Map时不报错() {
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                "echo test", WORK_DIR, null, false, Map.of());

        assertThat(pb).isNotNull();
    }

    // ─────────────────────────────────────────────
    //  大小写敏感性 — 黑名单用 toUpperCase 比较
    // ─────────────────────────────────────────────

    @Test
    void createShellProcess_小写path也被过滤() {
        ProcessBuilder pb = ShellProcessFactory.createShellProcess(
                "echo test", WORK_DIR, null, false,
                Map.of("path", "/evil/path"));

        // 黑名单使用 key.toUpperCase()，所以小写 "path" 也应被过滤
        assertThat(pb.environment().get("path")).isNotEqualTo("/evil/path");
    }

    // ─────────────────────────────────────────────
    //  工作目录设置
    // ─────────────────────────────────────────────

    @Test
    void createShellProcess_工作目录正确设置() {
        ProcessBuilder pb = ShellProcessFactory.createShellProcess("echo test", WORK_DIR);

        assertThat(pb.directory().toPath()).isEqualTo(WORK_DIR);
    }

    // ─────────────────────────────────────────────
    //  isWindows 方法
    // ─────────────────────────────────────────────

    @Test
    void isWindows_返回值与系统一致() {
        boolean expected = System.getProperty("os.name").toLowerCase().contains("win");
        assertThat(ShellProcessFactory.isWindows()).isEqualTo(expected);
    }
}
