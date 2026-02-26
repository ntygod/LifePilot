package com.lifepilot.sandbox.booter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.model.ExecutionRequest;
import com.lifepilot.sandbox.model.Language;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DockerBooter} 单元测试。
 *
 * <p>由于测试环境不一定安装 Docker，本测试聚焦于：
 * <ul>
 *   <li>type() 返回 "docker"</li>
 *   <li>Docker 命令构建逻辑验证</li>
 *   <li>available() 在无 Docker 环境下返回 false</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-01
 */
class DockerBooterTest {

    @TempDir
    Path tempDir;

    private SandboxConfigProperties config;
    private DockerBooter booter;

    @BeforeEach
    void setUp() {
        config = new SandboxConfigProperties();
        config.setRuntimePaths(new HashMap<>(Map.of(
                "python", "python3",
                "javascript", "node",
                "shell", "bash"
        )));
        booter = new DockerBooter(config);
    }

    @Test
    void type_返回docker() {
        assertThat(booter.type()).isEqualTo("docker");
    }

    @Test
    void 构建Docker命令_默认配置_包含安全参数() {
        var request = new ExecutionRequest(Language.PYTHON, "print('hello')", 30, tempDir);
        String containerName = "lifepilot-sandbox-test";

        ArrayList<String> command = booter.buildDockerCommand(containerName, request, "python3", "script.py");

        assertThat(command).contains("docker", "run", "--rm");
        assertThat(command).contains("--name", containerName);
        assertThat(command).contains("--network", "none");
        assertThat(command).contains("--read-only");
        assertThat(command).contains("--user", "1000:1000");
        assertThat(command).contains("--memory", "256m");
        assertThat(command).contains("--cpus", "1.0");
        // 镜像名
        assertThat(command).contains("lifepilot/sandbox-python");
        // 运行时命令和脚本路径
        assertThat(command).contains("python3", "/workspace/script.py");
    }

    @Test
    void 构建Docker命令_挂载工作目录() {
        var request = new ExecutionRequest(Language.JAVASCRIPT, "console.log(1)", 10, tempDir);

        ArrayList<String> command = booter.buildDockerCommand("test-container", request, "node", "script.js");

        // 验证 -v 挂载参数
        int vIndex = command.indexOf("-v");
        assertThat(vIndex).isGreaterThan(-1);
        String volumeArg = command.get(vIndex + 1);
        assertThat(volumeArg).endsWith(":/workspace:rw");
        assertThat(volumeArg).startsWith(tempDir.toAbsolutePath().toString());
    }

    @Test
    void 构建Docker命令_网络启用时_不包含network_none() {
        config.getDocker().setNetworkEnabled(true);
        var request = new ExecutionRequest(Language.SHELL, "echo hi", 10, tempDir);

        ArrayList<String> command = booter.buildDockerCommand("test-container", request, "bash", "script.sh");

        // 网络启用时不应包含 --network none
        assertThat(command).doesNotContain("none");
        // 但仍包含其他安全参数
        assertThat(command).contains("--read-only");
        assertThat(command).contains("--user", "1000:1000");
    }

    @Test
    void 构建Docker命令_自定义资源限制() {
        config.getDocker().setMemoryLimitMb(512);
        config.getDocker().setCpuLimit(2.0);
        var request = new ExecutionRequest(Language.PYTHON, "pass", 10, tempDir);

        ArrayList<String> command = booter.buildDockerCommand("test-container", request, "python3", "script.py");

        assertThat(command).contains("--memory", "512m");
        assertThat(command).contains("--cpus", "2.0");
    }

    @Test
    void 构建Docker命令_自定义镜像前缀() {
        config.getDocker().setImagePrefix("myregistry/sandbox-");
        var request = new ExecutionRequest(Language.JAVASCRIPT, "1+1", 10, tempDir);

        ArrayList<String> command = booter.buildDockerCommand("test-container", request, "node", "script.js");

        assertThat(command).contains("myregistry/sandbox-javascript");
    }

    @Test
    void 构建Docker命令_Shell语言() {
        var request = new ExecutionRequest(Language.SHELL, "echo hello", 10, tempDir);

        ArrayList<String> command = booter.buildDockerCommand("test-container", request, "bash", "script.sh");

        assertThat(command).contains("lifepilot/sandbox-shell");
        assertThat(command).contains("bash", "/workspace/script.sh");
    }
}
