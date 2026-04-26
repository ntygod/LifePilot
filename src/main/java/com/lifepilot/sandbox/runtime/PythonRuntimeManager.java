package com.lifepilot.sandbox.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.sandbox.config.SandboxConfigProperties;

/**
 * 捆绑 Python 运行时生命周期管理器。
 *
 * <p>负责查询 {@code ~/.zhiwei/python/} 安装状态、提供 Python 可执行文件路径。
 * 安装/卸载/启用/禁用流程由后续 Task 9 实现，本类仅落地查询接口与 installingState 占位字段。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
public class PythonRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(PythonRuntimeManager.class);

    private final SandboxConfigProperties config;

    /** 安装中状态共享引用 — 供 SSE 推送和 checkStatus 共用，由 Task 9 install() 写入。 */
    private final AtomicReference<RuntimeStatus> installingState = new AtomicReference<>();

    public PythonRuntimeManager(SandboxConfigProperties config) {
        this.config = config;
    }

    /**
     * 查询当前 Python 运行时状态，按以下优先级判定：
     * <ol>
     *   <li>安装中（最不稳态）</li>
     *   <li>配置强制禁用</li>
     *   <li>VERSION 文件不存在 → NotInstalled</li>
     *   <li>VERSION 内容与 bundledVersion 不一致 → InstallFailed</li>
     *   <li>Python 可执行文件缺失 → InstallFailed</li>
     *   <li>全部通过 → Ready</li>
     * </ol>
     */
    public RuntimeStatus checkStatus() {
        // 1. 安装中优先（最不稳态）
        var installing = installingState.get();
        if (installing != null) {
            return installing;
        }

        var pythonConfig = config.getRuntime().getPython();
        Path installPath = resolveInstallPath();

        // 2. 配置强制禁用
        if (pythonConfig.isDisabled()) {
            return new RuntimeStatus.Disabled();
        }

        // 3. 文件不存在 → NotInstalled
        Path versionFile = installPath.resolve("VERSION");
        if (!Files.exists(versionFile)) {
            return new RuntimeStatus.NotInstalled();
        }

        // 4. VERSION 匹配检查
        try {
            String installed = Files.readString(versionFile).trim();
            String expected = pythonConfig.getBundledVersion();
            if (!installed.equals(expected)) {
                return new RuntimeStatus.InstallFailed(
                        "版本不匹配: 已安装 %s, 期望 %s".formatted(installed, expected));
            }

            // 5. python 可执行文件存在性检查 — 兼容两种 python-build-standalone 布局
            //    Windows: <installPath>/python.exe；Unix: <installPath>/bin/python
            //    任一存在即视为安装完整，避免跨平台测试夹具与运行时 OS 不一致时误判。
            Path winExe = installPath.resolve("python.exe");
            Path unixExe = installPath.resolve("bin/python");
            if (!Files.exists(winExe) && !Files.exists(unixExe)) {
                return new RuntimeStatus.InstallFailed(
                        "Python 可执行文件缺失: " + getPythonExecutable());
            }

            long diskBytes = computeDiskUsage(installPath);
            return new RuntimeStatus.Ready(installed, diskBytes);
        } catch (IOException e) {
            log.warn("读取 VERSION 文件失败: {}", e.getMessage());
            return new RuntimeStatus.InstallFailed("VERSION 文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 返回 Python 可执行文件路径（无论当前状态是否 Ready）。
     *
     * <p>Windows 下为 {@code <installPath>/python.exe}，*nix 下为 {@code <installPath>/bin/python}。</p>
     */
    public Path getPythonExecutable() {
        Path installPath = resolveInstallPath();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        return isWindows
                ? installPath.resolve("python.exe")
                : installPath.resolve("bin/python");
    }

    /** 解析 installPath 配置，替换 {@code ${user.home}} 占位符。 */
    Path resolveInstallPath() {
        String raw = config.getRuntime().getPython().getInstallPath();
        String resolved = raw.replace("${user.home}", System.getProperty("user.home"));
        return Paths.get(resolved);
    }

    /** 由后续 Task 9 PythonRuntimeManager.install() 写入安装中状态。 */
    void setInstalling(RuntimeStatus.Installing state) {
        installingState.set(state);
    }

    /** 由后续 Task 9 install()/installFailed() 收尾时清除安装中状态。 */
    void clearInstalling() {
        installingState.set(null);
    }

    /** 递归累计安装目录磁盘占用（字节）。读 IO 失败的单文件按 0 计入，避免整体失败。 */
    private long computeDiskUsage(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
