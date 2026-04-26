package com.lifepilot.sandbox.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lifepilot.sandbox.config.SandboxConfigProperties;
import com.lifepilot.sandbox.util.SandboxUtils;

/**
 * 捆绑 Python 运行时生命周期管理器。
 *
 * <p>负责查询 {@code ~/.zhiwei/python/} 安装状态、提供 Python 可执行文件路径，
 * 以及 install / uninstall / enable / disable 4 个生命周期方法。</p>
 *
 * <p>install 流程：下载 tar.zst → SHA-256 校验 → 解压 → 写 VERSION → emit done。
 * 进度通过 {@link RuntimeInstallProgressEmitter} 推送 SSE，并按 1MB 阈值 throttle，
 * 避免 64KB 缓冲粒度下产生 4000+ 次 SSE 事件造成性能压力。</p>
 *
 * <p>所有生命周期变更（成功 / 失败）都会通过 {@link RuntimeInstallHistoryRepository}
 * 写入审计历史；historyRepo 为 null 时跳过（仅单元测试或纯属性绑定场景）。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
public class PythonRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(PythonRuntimeManager.class);

    /** 进度 SSE 推送阈值：每跨过 1MB 推一次，避免高频 emit 造成 SSE 风暴。 */
    private static final long EMIT_THRESHOLD_BYTES = 1024L * 1024L;

    /** done 事件推送后延迟清除 installingState 的毫秒数，确保前端能收到终态事件。 */
    private static final long DONE_HOLD_MS = 500L;

    private final SandboxConfigProperties config;

    /** 安装/卸载历史审计仓库，可空（无 Spring 容器场景）。 */
    @Nullable
    private final RuntimeInstallHistoryRepository historyRepo;

    /** 安装中状态共享引用 — 供 SSE 推送和 checkStatus 共用。 */
    private final AtomicReference<RuntimeStatus> installingState = new AtomicReference<>();

    /**
     * 最近一次 install 失败的原因（内存级，进程重启丢失）。
     *
     * <p>必须有这个字段，因为 install 失败时 VERSION 文件未写入，仅靠
     * {@link #checkStatus()} 检测文件状态会返回 NotInstalled，UI 会"恢复到点击前"，
     * 用户以为啥都没发生 — 实际是装失败了。该字段让 checkStatus 在文件缺失时仍能
     * 返回 InstallFailed(reason)，保证用户能看到失败原因 + 重试按钮。</p>
     *
     * <p>清空时机：每次 install 重新开始 / uninstall / disable 触发时。</p>
     */
    private final AtomicReference<String> lastInstallError = new AtomicReference<>();

    public PythonRuntimeManager(SandboxConfigProperties config) {
        this(config, null);
    }

    public PythonRuntimeManager(SandboxConfigProperties config,
                                @Nullable RuntimeInstallHistoryRepository historyRepo) {
        this.config = config;
        this.historyRepo = historyRepo;
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

        // 3. 最近 install 失败 → InstallFailed（带 reason 让 UI 显示真实原因 + 重试按钮）
        //    必须放在 VERSION 文件检查之前 — install 失败时 VERSION 未写入，否则会被误判为 NotInstalled
        String lastError = lastInstallError.get();
        if (lastError != null) {
            return new RuntimeStatus.InstallFailed(lastError);
        }

        // 4. 文件不存在 → NotInstalled
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

    /**
     * 异步安装捆绑 Python 运行时。
     *
     * <p>流程：拼装下载 URL → 推 downloading 进度（throttled）→ 推 extracting →
     * 解压 tar.zst → 写 VERSION → 推 done → 写历史。任一阶段失败：emitFailed + 写失败历史 + 抛出。</p>
     *
     * <p>所有进度回调都按 1MB 阈值 throttle，避免 64KB 缓冲粒度下产生 4000+ 次 SSE 推送。</p>
     *
     * @param emitter SSE 进度推送器（不可为 null）
     * @return 安装完成的 future；失败时 join 抛 RuntimeException
     */
    public CompletableFuture<Void> install(RuntimeInstallProgressEmitter emitter) {
        Path installPath = resolveInstallPath();
        // installPath 必须有父目录，否则 tarball 无处落地、解压目录无法定位 — 显式抛出避免 NPE 在 catch 里被打成 "null"
        Path parent = installPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "installPath 必须是带父目录的非根路径: " + installPath);
        }
        Path tarball = parent.resolve(installPath.getFileName() + ".tar.zst");
        // 进入新一轮 install,清掉上次失败原因 — 让 checkStatus 在 install 中走 Installing 路径而非 InstallFailed
        lastInstallError.set(null);

        return CompletableFuture.runAsync(() -> {
            var pythonConfig = config.getRuntime().getPython();
            long startTime = System.currentTimeMillis();
            String version = pythonConfig.getBundledVersion();

            try {
                String platform = detectPlatform();
                String arch = detectArch();

                String fileUrl = pythonConfig.getDownloadUrlTemplate()
                        .replace("{version}", version)
                        .replace("{platform}", platform)
                        .replace("{arch}", arch);
                String sha256Url = pythonConfig.getSha256UrlTemplate()
                        .replace("{version}", version)
                        .replace("{platform}", platform)
                        .replace("{arch}", arch);

                // 状态：downloading（先 emit 一次让前端切到进度页）
                installingState.set(new RuntimeStatus.Installing("downloading", 0, 0));
                emitter.emit(installingState.get());

                // 安装可重入：清理上次中途崩溃可能留下的半解压残留 — 也覆盖 Ready 后用户在 setting 重装的场景
                if (Files.exists(installPath)) {
                    log.info("检测到已有 Python 运行时目录，先清理残留: path={}", installPath);
                    SandboxUtils.deleteDirectoryRecursively(installPath);
                }

                // throttled 进度回调 — 终态或跨过 1MB 阈值才推；total 未知时仅按阈值推
                final long[] lastEmittedBytes = {0L};
                new PythonRuntimeDownloader().download(fileUrl, sha256Url, tarball, (bytes, total) -> {
                    boolean atTerminal = total > 0 && bytes >= total;
                    boolean crossedThreshold = bytes - lastEmittedBytes[0] >= EMIT_THRESHOLD_BYTES;
                    if (atTerminal || crossedThreshold) {
                        lastEmittedBytes[0] = bytes;
                        installingState.set(new RuntimeStatus.Installing("downloading", bytes, total));
                        emitter.emit(installingState.get());
                    }
                });

                // 状态：extracting
                installingState.set(new RuntimeStatus.Installing("extracting", 0, 0));
                emitter.emit(installingState.get());

                // 解压 tar.zst → installPath 的父目录（python-build-standalone tarball 内含 python/ 子目录）
                extractTarZst(tarball, parent);

                // 写 VERSION 文件（如果 tarball 没带）
                Path versionFile = installPath.resolve("VERSION");
                if (!Files.exists(versionFile)) {
                    Files.writeString(versionFile, version);
                }

                // 状态：done
                installingState.set(new RuntimeStatus.Installing("done", 1, 1));
                emitter.emit(installingState.get());

                long duration = System.currentTimeMillis() - startTime;
                if (historyRepo != null) {
                    historyRepo.insert("python", version, "install", "success", null, duration);
                }

                log.info("Python 运行时安装完成: path={}, version={}, durationMs={}",
                        installPath, version, duration);
            } catch (Exception e) {
                log.error("Python 运行时安装失败: version={}, error={}", version, e.getMessage(), e);
                long duration = System.currentTimeMillis() - startTime;
                if (historyRepo != null) {
                    historyRepo.insert("python", version, "install", "failed",
                            e.getMessage(), duration);
                }
                installingState.set(null);
                // 持久化失败原因到内存,让后续 checkStatus 返回 InstallFailed(reason) 而非 NotInstalled
                lastInstallError.set(e.getMessage());
                emitter.emitFailed(e.getMessage());
                throw new RuntimeException("安装失败", e);
            } finally {
                // 清理临时 tarball；忽略失败（最坏情况留个临时文件，不影响 Ready 判定）
                try { Files.deleteIfExists(tarball); } catch (IOException ignored) {}
                // 延迟清除 installing 状态，让 SSE done 事件被前端收到再切回 Ready
                try {
                    Thread.sleep(DONE_HOLD_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                installingState.set(null);
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 卸载 Python 运行时 — 递归删除安装目录。
     *
     * @throws IOException 目录删除失败
     */
    public void uninstall() throws IOException {
        long startTime = System.currentTimeMillis();
        String version = config.getRuntime().getPython().getBundledVersion();
        Path installPath = resolveInstallPath();
        // 用户主动 reset，清掉失败原因，避免卸载后仍显示 InstallFailed
        lastInstallError.set(null);
        try {
            if (Files.exists(installPath)) {
                SandboxUtils.deleteDirectoryRecursively(installPath);
            }
            long duration = System.currentTimeMillis() - startTime;
            if (historyRepo != null) {
                historyRepo.insert("python", version, "uninstall", "success", null, duration);
            }
            log.info("Python 运行时已卸载: path={}", installPath);
        } catch (IOException e) {
            if (historyRepo != null) {
                historyRepo.insert("python", version, "uninstall", "failed",
                        e.getMessage(), System.currentTimeMillis() - startTime);
            }
            throw e;
        }
    }

    /**
     * 禁用捆绑 Python 运行时 — 文件保留，但 checkStatus 返回 Disabled。
     *
     * <p>注意：仅修改内存中的配置属性；持久化由调用方（设置控制器）负责。</p>
     */
    public void disable() {
        config.getRuntime().getPython().setDisabled(true);
        // 用户主动 disable,失败原因已不相关
        lastInstallError.set(null);
        if (historyRepo != null) {
            historyRepo.insert("python", config.getRuntime().getPython().getBundledVersion(),
                    "disable", "success", null, 0L);
        }
        log.info("Python 运行时已禁用");
    }

    /**
     * 启用捆绑 Python 运行时 — 反向操作，让 checkStatus 重新进入正常判定。
     */
    public void enable() {
        config.getRuntime().getPython().setDisabled(false);
        if (historyRepo != null) {
            historyRepo.insert("python", config.getRuntime().getPython().getBundledVersion(),
                    "enable", "success", null, 0L);
        }
        log.info("Python 运行时已启用");
    }

    /** 解析 installPath 配置，替换 {@code ${user.home}} 占位符。 */
    Path resolveInstallPath() {
        String raw = config.getRuntime().getPython().getInstallPath();
        String resolved = raw.replace("${user.home}", System.getProperty("user.home"));
        return Paths.get(resolved);
    }

    /** 由 install() 写入安装中状态（包级可见，便于状态机测试覆盖）。 */
    void setInstalling(RuntimeStatus.Installing state) {
        installingState.set(state);
    }

    /** 由 install() / 测试收尾时清除安装中状态。 */
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

    /** 探测当前操作系统平台，对齐 python-build-standalone 命名约定。 */
    private static String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "macos";
        return "linux";
    }

    /** 探测 CPU 架构 — aarch64/arm64 归一化为 arm64，其余视作 x86_64。 */
    private static String detectArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        return "x86_64";
    }

    /**
     * 解压 tar.zst 归档到目标目录。
     *
     * <p>包含 zip-slip 防御：每个 entry 的 normalized 路径必须仍位于 outDir 之下。
     * Owner-execute 位（0100）会保留到目标文件，其余权限位丢弃（Java NIO 跨平台限制）。</p>
     *
     * @param tarZst 源 tar.zst 文件
     * @param outDir 输出根目录（自动创建）
     * @throws IOException 解压失败 / 路径越权
     */
    private static void extractTarZst(Path tarZst, Path outDir) throws IOException {
        // 先 normalize：避免跨平台 / 符号链接 / 大小写不敏感文件系统下 startsWith 语义不一致
        Path normalizedOut = outDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOut);
        try (var in = Files.newInputStream(tarZst);
             var zstd = new com.github.luben.zstd.ZstdInputStream(in);
             var tar = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(zstd)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path target = normalizedOut.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedOut)) {
                    throw new IOException("tar entry 越权: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    // REPLACE_EXISTING 作为双重防御：install 入口已清残留，但保留以防极端竞态
                    Files.copy(tar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if ((entry.getMode() & 0100) != 0) {
                        target.toFile().setExecutable(true);
                    }
                }
            }
        }
    }
}
