package com.lifepilot.sandbox.booter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.model.ExecutionRequest;
import com.lifepilot.sandbox.model.ExecutionResult;
import com.lifepilot.sandbox.model.ExecutionState;
import com.lifepilot.sandbox.model.Language;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProcessBooter} 单元测试。
 *
 * <p>需要本地 Node.js 和 Python 环境，CI 无对应运行时时自动跳过。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
@EnabledIf("runtimesAvailable")
class ProcessBooterTest {

    /** 检查 node 和 python 命令是否可用，不可用时整个测试类跳过。 */
    static boolean runtimesAvailable() {
        try {
            new ProcessBuilder("node", "--version").start().waitFor();
            new ProcessBuilder("python", "--version").start().waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @TempDir
    Path tempDir;

    private SandboxConfigProperties config;
    private ProcessBooter booter;

    @BeforeEach
    void setUp() {
        config = new SandboxConfigProperties();
        // Windows 上 python3 不可用，使用 python
        var runtimePaths = new HashMap<>(Map.of(
                "python", "python",
                "javascript", "node",
                "shell", "bash"
        ));
        config.setRuntimePaths(runtimePaths);
        booter = new ProcessBooter(config);
        booter.boot(tempDir).join();
    }

    @AfterEach
    void tearDown() {
        booter.shutdown();
    }

    @Test
    void available_始终返回true() {
        assertThat(booter.available()).isTrue();
    }

    @Test
    void type_返回process() {
        assertThat(booter.type()).isEqualTo("process");
    }

    @Test
    void 构建命令应直接调用运行时避免Shell包装() {
        var scriptFile = tempDir.resolve("direct-run.js");

        var command = booter.buildCommand("node", scriptFile);

        assertThat(command).containsExactly("node", scriptFile.toString());
    }

    @Test
    void 执行Node脚本_正常输出() {
        var request = new ExecutionRequest(
                Language.JAVASCRIPT,
                "console.log('hello from node');",
                10,
                tempDir
        );

        ExecutionResult result = booter.execute(request);

        assertThat(result.state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(result.stdout().trim()).isEqualTo("hello from node");
        assertThat(result.exitCode()).isZero();
        assertThat(result.durationMs()).isGreaterThan(0);
    }

    @Test
    void 执行Node脚本_stderr输出() {
        var request = new ExecutionRequest(
                Language.JAVASCRIPT,
                "console.error('error message');",
                10,
                tempDir
        );

        ExecutionResult result = booter.execute(request);

        assertThat(result.state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(result.stderr().trim()).isEqualTo("error message");
    }

    @Test
    void 执行Node脚本_非零退出码() {
        var request = new ExecutionRequest(
                Language.JAVASCRIPT,
                "process.exit(42);",
                10,
                tempDir
        );

        ExecutionResult result = booter.execute(request);

        assertThat(result.state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(result.exitCode()).isEqualTo(42);
    }

    @Test
    void 执行超时_返回TIMEOUT状态() {
        // Node 无限循环，1 秒超时
        var request = new ExecutionRequest(
                Language.JAVASCRIPT,
                "while(true) {}",
                1,
                tempDir
        );

        ExecutionResult result = booter.execute(request);

        assertThat(result.state()).isEqualTo(ExecutionState.TIMEOUT);
        assertThat(result.exitCode()).isEqualTo(-1);
    }

    @Test
    void 执行后临时脚本文件被清理() throws IOException {
        var request = new ExecutionRequest(
                Language.JAVASCRIPT,
                "console.log('cleanup test');",
                10,
                tempDir
        );

        booter.execute(request);

        // 临时脚本文件应已被清理，工作目录中不应有 .js 文件
        try (var files = Files.list(tempDir)) {
            long jsFileCount = files
                    .filter(p -> p.toString().endsWith(".js"))
                    .count();
            assertThat(jsFileCount).isZero();
        }
    }

    @Test
    void 输出截断_超过maxOutputBytes() {
        config.setMaxOutputBytes(32);
        // 生成大量输出
        var request = new ExecutionRequest(
                Language.JAVASCRIPT,
                "process.stdout.write('A'.repeat(1000));",
                10,
                tempDir
        );

        ExecutionResult result = booter.execute(request);

        assertThat(result.state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(result.stdout().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(32);
    }

    @Test
    void 空输出_正常处理() {
        var request = new ExecutionRequest(
                Language.JAVASCRIPT,
                "// 无输出",
                10,
                tempDir
        );

        ExecutionResult result = booter.execute(request);

        assertThat(result.state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void Python脚本_正常执行() {
        var request = new ExecutionRequest(
                Language.PYTHON,
                "print('hello from python')",
                10,
                tempDir
        );

        ExecutionResult result = booter.execute(request);

        assertThat(result.state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(result.stdout().trim()).isEqualTo("hello from python");
        assertThat(result.exitCode()).isZero();
    }
}
