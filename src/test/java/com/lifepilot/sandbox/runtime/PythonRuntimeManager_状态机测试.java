package com.lifepilot.sandbox.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.sandbox.config.SandboxConfigProperties;

/**
 * PythonRuntimeManager 状态机测试 — 校验 checkStatus() 在 4 种核心场景下的状态判定。
 *
 * @author zsg
 * @since 2026-04-26
 */
class PythonRuntimeManager_状态机测试 {

    @Test
    void 安装目录不存在时返回NotInstalled(@TempDir Path tempDir) {
        var config = new SandboxConfigProperties();
        config.getRuntime().getPython().setBundledVersion("3.12.13");
        var zhiweiPaths = mockZhiweiPaths(tempDir.resolve("not-exists"));
        var manager = new PythonRuntimeManager(config, zhiweiPaths);

        assertThat(manager.checkStatus()).isInstanceOf(RuntimeStatus.NotInstalled.class);
    }

    @Test
    void 配置disabled为true时返回Disabled(@TempDir Path tempDir) throws Exception {
        var pythonDir = tempDir.resolve("python");
        Files.createDirectories(pythonDir);
        Files.writeString(pythonDir.resolve("VERSION"), "3.12.13");
        var config = new SandboxConfigProperties();
        config.getRuntime().getPython().setBundledVersion("3.12.13");
        config.getRuntime().getPython().setDisabled(true);
        var zhiweiPaths = mockZhiweiPaths(pythonDir);

        var manager = new PythonRuntimeManager(config, zhiweiPaths);

        assertThat(manager.checkStatus()).isInstanceOf(RuntimeStatus.Disabled.class);
    }

    @Test
    void VERSION文件存在且版本匹配时返回Ready(@TempDir Path tempDir) throws Exception {
        var pythonDir = tempDir.resolve("python");
        Files.createDirectories(pythonDir.resolve("bin"));
        Files.writeString(pythonDir.resolve("VERSION"), "3.12.13");
        Files.writeString(pythonDir.resolve("bin/python"), "#!/bin/sh\necho fake");
        var config = new SandboxConfigProperties();
        config.getRuntime().getPython().setBundledVersion("3.12.13");
        var zhiweiPaths = mockZhiweiPaths(pythonDir);

        var manager = new PythonRuntimeManager(config, zhiweiPaths);
        var status = manager.checkStatus();

        assertThat(status).isInstanceOf(RuntimeStatus.Ready.class);
        assertThat(((RuntimeStatus.Ready) status).version()).isEqualTo("3.12.13");
    }

    @Test
    void VERSION文件不匹配时返回InstallFailed(@TempDir Path tempDir) throws Exception {
        var pythonDir = tempDir.resolve("python");
        Files.createDirectories(pythonDir);
        Files.writeString(pythonDir.resolve("VERSION"), "3.11.0");
        var config = new SandboxConfigProperties();
        config.getRuntime().getPython().setBundledVersion("3.12.13");
        var zhiweiPaths = mockZhiweiPaths(pythonDir);

        var manager = new PythonRuntimeManager(config, zhiweiPaths);

        assertThat(manager.checkStatus()).isInstanceOf(RuntimeStatus.InstallFailed.class);
    }

    @Test
    void 安装中状态优先于其他判定(@TempDir Path tempDir) throws Exception {
        var pythonDir = tempDir.resolve("python");
        Files.createDirectories(pythonDir.resolve("bin"));
        Files.writeString(pythonDir.resolve("VERSION"), "3.12.13");
        Files.writeString(pythonDir.resolve("bin/python"), "#!/bin/sh\necho fake");

        var config = new SandboxConfigProperties();
        config.getRuntime().getPython().setBundledVersion("3.12.13");
        var zhiweiPaths = mockZhiweiPaths(pythonDir);
        var manager = new PythonRuntimeManager(config, zhiweiPaths);

        // 即便文件齐全本可返回 Ready，setInstalling 也应让 checkStatus 优先返回 Installing
        manager.setInstalling(new RuntimeStatus.Installing("downloading", 50, 100));
        var status = manager.checkStatus();

        assertThat(status).isInstanceOf(RuntimeStatus.Installing.class);
        var installing = (RuntimeStatus.Installing) status;
        assertThat(installing.phase()).isEqualTo("downloading");
        assertThat(installing.percent()).isEqualTo(50);

        // clearInstalling 后回到 Ready
        manager.clearInstalling();
        assertThat(manager.checkStatus()).isInstanceOf(RuntimeStatus.Ready.class);
    }

    @Test
    void Windows系统下getPythonExecutable返回pythonExe(@TempDir Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"),
                "仅在 Windows 系统验证");
        var config = new SandboxConfigProperties();
        config.getRuntime().getPython().setBundledVersion("3.12.13");
        var zhiweiPaths = mockZhiweiPaths(tempDir.resolve("python"));
        var manager = new PythonRuntimeManager(config, zhiweiPaths);

        Path exe = manager.getPythonExecutable();
        assertThat(exe.getFileName().toString()).isEqualTo("python.exe");
        assertThat(exe.getParent()).isEqualTo(tempDir.resolve("python"));
    }

    @Test
    void 非Windows系统下getPythonExecutable返回binPython(@TempDir Path tempDir) {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"),
                "仅在非 Windows 系统验证");
        var config = new SandboxConfigProperties();
        config.getRuntime().getPython().setBundledVersion("3.12.13");
        var zhiweiPaths = mockZhiweiPaths(tempDir.resolve("python"));
        var manager = new PythonRuntimeManager(config, zhiweiPaths);

        Path exe = manager.getPythonExecutable();
        assertThat(exe.getFileName().toString()).isEqualTo("python");
        assertThat(exe.getParent().getFileName().toString()).isEqualTo("bin");
    }

    private ZhiweiPaths mockZhiweiPaths(Path pythonDir) {
        var zhiweiPaths = mock(ZhiweiPaths.class);
        when(zhiweiPaths.home(ZhiweiPaths.DIR_RUNTIME_PYTHON)).thenReturn(pythonDir);
        return zhiweiPaths;
    }
}
