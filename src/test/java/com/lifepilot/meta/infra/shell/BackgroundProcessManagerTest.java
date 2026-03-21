package com.lifepilot.meta.infra.shell;

import com.lifepilot.meta.config.MetaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BackgroundProcessManager 后台进程管理器单元测试。
 *
 * @author zsg
 * @since 2026-03-20
 */
class BackgroundProcessManagerTest {

    private BackgroundProcessManager manager;
    private MetaProperties.Infra.Process processConfig;

    @BeforeEach
    void setUp() {
        processConfig = new MetaProperties.Infra.Process();
        processConfig.setMaxConcurrent(3);
        processConfig.setMaxOutputBufferSize(10000);
        processConfig.setIdleTimeoutMinutes(30);
        manager = new BackgroundProcessManager(processConfig);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void 启动后台进程_返回sessionId() throws Exception {
        String sessionId = manager.startProcess("cmd /c echo hello", Path.of(System.getProperty("user.home")));
        assertThat(sessionId).isNotBlank().hasSize(8);

        // 等待进程完成
        Thread.sleep(500);

        var processes = manager.listProcesses();
        assertThat(processes).hasSize(1);
        assertThat(processes.getFirst().sessionId()).isEqualTo(sessionId);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void 启动后台进程_Linux() throws Exception {
        String sessionId = manager.startProcess("echo hello", Path.of(System.getProperty("user.home")));
        assertThat(sessionId).isNotBlank().hasSize(8);
        Thread.sleep(500);
        var processes = manager.listProcesses();
        assertThat(processes).hasSize(1);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void 读取进程输出() throws Exception {
        String sessionId = manager.startProcess("cmd /c echo test-output", Path.of(System.getProperty("user.home")));
        Thread.sleep(500);
        String output = manager.readOutput(sessionId);
        assertThat(output).contains("test-output");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void 读取进程输出_中文不乱码() throws Exception {
        String sessionId = manager.startProcess("Write-Output '中文输出'", Path.of(System.getProperty("user.home")));
        Thread.sleep(500);
        String output = manager.readOutput(sessionId);
        assertThat(output).contains("中文输出");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void kill终止进程() throws Exception {
        // 启动一个长时间运行的进程
        String sessionId = manager.startProcess("cmd /c ping -n 100 127.0.0.1", Path.of(System.getProperty("user.home")));
        Thread.sleep(300);

        // 确认进程在运行
        var processes = manager.listProcesses();
        assertThat(processes.getFirst().state()).isEqualTo(ProcessState.RUNNING);

        // 终止进程
        manager.killProcess(sessionId);

        processes = manager.listProcesses();
        assertThat(processes.getFirst().state()).isEqualTo(ProcessState.KILLED);
    }

    @Test
    void 并发限制_超过最大数量抛异常() throws Exception {
        processConfig.setMaxConcurrent(1);

        // 启动第一个长时间运行的进程
        String osName = System.getProperty("os.name").toLowerCase();
        String longCmd = osName.contains("win") ? "cmd /c ping -n 100 127.0.0.1" : "sleep 100";
        manager.startProcess(longCmd, Path.of(System.getProperty("user.home")));
        Thread.sleep(200);

        // 第二个应该失败
        assertThatThrownBy(() ->
                manager.startProcess(longCmd, Path.of(System.getProperty("user.home"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("后台进程数已达上限");
    }

    @Test
    void 不存在的sessionId_抛异常() {
        assertThatThrownBy(() -> manager.readOutput("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("后台进程不存在");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void 进程完成后状态变为COMPLETED() throws Exception {
        manager.startProcess("cmd /c echo done", Path.of(System.getProperty("user.home")));
        Thread.sleep(1000);

        var processes = manager.listProcesses();
        assertThat(processes.getFirst().state()).isEqualTo(ProcessState.COMPLETED);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void 进程失败后状态变为FAILED() throws Exception {
        manager.startProcess("cmd /c exit 1", Path.of(System.getProperty("user.home")));
        Thread.sleep(1000);

        var processes = manager.listProcesses();
        assertThat(processes.getFirst().state()).isEqualTo(ProcessState.FAILED);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void 向已结束进程写入_抛异常() throws Exception {
        String sessionId = manager.startProcess("cmd /c echo done", Path.of(System.getProperty("user.home")));
        Thread.sleep(1000);

        assertThatThrownBy(() -> manager.writeInput(sessionId, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("进程已结束");
    }

    @Test
    void 空闲清理_移除超时进程() throws Exception {
        // 设置极短的空闲超时
        processConfig.setIdleTimeoutMinutes(0);

        String osName = System.getProperty("os.name").toLowerCase();
        String cmd = osName.contains("win") ? "cmd /c echo test" : "echo test";
        manager.startProcess(cmd, Path.of(System.getProperty("user.home")));
        Thread.sleep(500);

        // 手动触发清理
        manager.cleanupIdleProcesses();

        assertThat(manager.listProcesses()).isEmpty();
    }
}
