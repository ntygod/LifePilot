package com.lifepilot.meta.infra.code.kernel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于长驻进程的持久内核基类 — 封装 Python/JavaScript 内核的公共生命周期管理。
 *
 * <p>子类只需提供 REPL 脚本内容、启动命令和临时文件后缀。
 * 基类负责进程启动/销毁、JSON 行级通信、超时控制、状态管理和资源清理。</p>
 *
 * <p>线程安全：execute/inspect/reset 使用 synchronized 保证串行。
 * 超时控制通过类级共享 VirtualThreadPerTaskExecutor + Future.get(timeout) 实现。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public sealed class ProcessKernelBase implements PersistentKernel
        permits PythonKernel, JavaScriptKernel {

    private static final Logger log = LoggerFactory.getLogger(ProcessKernelBase.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String id;
    private final int maxOutputChars;
    private final String runtimeCmd;
    private final List<String> runtimeArgs;
    private final String scriptSuffix;
    private final String langName;
    private final String script;
    private final AtomicReference<KernelState> stateRef = new AtomicReference<>(KernelState.STARTING);

    /** 类级共享 executor，避免每次 sendRequest 创建新实例。 */
    private final ExecutorService requestExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private Process process;
    private BufferedWriter processStdin;
    private BufferedReader processStdout;
    private Path tempScriptFile;

    /**
     * 构造基类并启动进程。
     *
     * <p>所有参数在 super() 调用时直接传入，避免从构造函数中调用子类虚方法
     * （子类字段在 super() 返回前尚未初始化）。</p>
     *
     * @param kernelId       内核唯一标识
     * @param maxOutputChars 输出最大字符数
     * @param runtimeCmd     启动命令（如 "python3" / "node"）
     * @param runtimeArgs    额外运行时参数（如 Python 的 "-u"），脚本路径会自动追加
     * @param scriptSuffix   临时文件后缀（如 ".py" / ".js"）
     * @param langName       语言名称（用于日志）
     * @param script         REPL 脚本内容
     */
    protected ProcessKernelBase(String kernelId, int maxOutputChars,
                                String runtimeCmd, List<String> runtimeArgs,
                                String scriptSuffix,
                                String langName, String script) {
        this.id = kernelId;
        this.maxOutputChars = maxOutputChars;
        this.runtimeCmd = runtimeCmd;
        this.runtimeArgs = runtimeArgs;
        this.scriptSuffix = scriptSuffix;
        this.langName = langName;
        this.script = script;
        startProcess();
    }

    @Override
    public String kernelId() {
        return id;
    }

    @Override
    public KernelState state() {
        return stateRef.get();
    }

    @Override
    public synchronized KernelExecutionResult execute(String code, int timeoutSeconds) {
        ensureAlive();
        stateRef.set(KernelState.BUSY);
        long startMs = System.currentTimeMillis();

        try {
            var request = Map.of("action", "execute", "code", code);
            Map<String, Object> response = sendRequest(request, timeoutSeconds);

            int durationMs = (int) (System.currentTimeMillis() - startMs);
            String stdout = truncate(stringVal(response, "stdout"), maxOutputChars);
            String stderr = truncate(stringVal(response, "stderr"), maxOutputChars);
            String error = stringVal(response, "error");

            stateRef.set(KernelState.READY);
            return new KernelExecutionResult(stdout, stderr, error, durationMs);
        } catch (Exception e) {
            int durationMs = (int) (System.currentTimeMillis() - startMs);
            stateRef.set(KernelState.ERROR);
            log.error("{} 内核执行失败: kernelId={}, error={}", langName, id, e.getMessage());
            return new KernelExecutionResult("", "", "内核执行异常: " + e.getMessage(), durationMs);
        }
    }

    @Override
    public synchronized Map<String, String> inspect() {
        ensureAlive();

        try {
            var request = Map.of("action", "inspect");
            Map<String, Object> response = sendRequest(request, 10);

            @SuppressWarnings("unchecked")
            Map<String, Object> variables = (Map<String, Object>) response.getOrDefault("variables", Map.of());
            Map<String, String> result = new HashMap<>();
            variables.forEach((k, v) -> result.put(k, String.valueOf(v)));
            return result;
        } catch (Exception e) {
            log.warn("{} 内核检查失败: kernelId={}, error={}", langName, id, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @Override
    public synchronized void reset() {
        ensureAlive();

        try {
            var request = Map.of("action", "reset");
            sendRequest(request, 10);
            log.info("{} 内核已重置: kernelId={}", langName, id);
        } catch (Exception e) {
            log.warn("{} 内核重置失败: kernelId={}, error={}", langName, id, e.getMessage());
        }
    }

    @Override
    public void close() {
        stateRef.set(KernelState.CLOSED);
        requestExecutor.close();
        destroyProcess();
        cleanupTempFile();
        log.info("{} 内核已关闭: kernelId={}", langName, id);
    }

    /**
     * 尝试重启已崩溃的内核进程。
     *
     * @return 重启成功返回 true
     */
    public synchronized boolean tryRestart() {
        if (stateRef.get() != KernelState.ERROR) {
            return false;
        }
        log.info("尝试重启 {} 内核: kernelId={}", langName, id);
        destroyProcess();
        cleanupTempFile();
        try {
            startProcess();
            return true;
        } catch (Exception e) {
            log.error("{} 内核重启失败: kernelId={}, error={}", langName, id, e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    /** 启动进程。 */
    private void startProcess() {
        try {
            tempScriptFile = Files.createTempFile("zhiwei-kernel-", scriptSuffix);
            Files.writeString(tempScriptFile, script, StandardCharsets.UTF_8);
            tempScriptFile.toFile().deleteOnExit();

            var command = new ArrayList<String>();
            command.add(runtimeCmd);
            command.addAll(runtimeArgs);
            command.add(tempScriptFile.toString());
            var pb = new ProcessBuilder(command);
            // 合并 stderr 到 stdout，避免 stderr 管道缓冲区满导致进程死锁
            pb.redirectErrorStream(true);
            // 子类按 runtime 决定环境变量（PYTHONUTF8 仅 Python 内核需要，JS 内核不应灌入）
            configureProcessEnvironment(pb.environment());
            process = pb.start();

            processStdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            processStdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            stateRef.set(KernelState.READY);
            log.info("{} 内核已启动: kernelId={}, pid={}", langName, id, process.pid());
        } catch (IOException e) {
            stateRef.set(KernelState.ERROR);
            log.error("{} 内核启动失败: kernelId={}, error={}", langName, id, e.getMessage());
            throw new IllegalStateException(langName + " 内核启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 向内核进程发送 JSON 请求并读取 JSON 响应。
     */
    private Map<String, Object> sendRequest(Map<String, ?> request, int timeoutSeconds) throws Exception {
        String jsonLine = MAPPER.writeValueAsString(request);

        Future<String> responseFuture = requestExecutor.submit(() -> {
            processStdin.write(jsonLine);
            processStdin.newLine();
            processStdin.flush();
            return processStdout.readLine();
        });

        try {
            String responseLine = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);

            if (responseLine == null) {
                throw new IOException(langName + " 内核进程已终止（stdin 关闭）");
            }

            return MAPPER.readValue(responseLine, MAP_TYPE);
        } catch (TimeoutException e) {
            responseFuture.cancel(true);
            throw new IOException(langName + " 内核执行超时: " + timeoutSeconds + " 秒", e);
        } catch (ExecutionException e) {
            throw new IOException(langName + " 内核通信异常: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * 子类钩子：按 runtime 配置进程环境变量。默认无操作；
     * Python 子类需注入 {@code PYTHONIOENCODING} / {@code PYTHONUTF8} 防 Windows
     * GBK 默认 stdio 编码导致 lone surrogate（参见 {@link PythonKernel}）。
     *
     * @param env ProcessBuilder 的环境变量映射（可读写）
     */
    protected void configureProcessEnvironment(java.util.Map<String, String> env) {
        // 默认空实现 — 子类按需 override
    }

    /** 检查进程是否存活。 */
    private void ensureAlive() {
        KernelState currentState = stateRef.get();
        if (currentState == KernelState.CLOSED) {
            throw new IllegalStateException(langName + " 内核已关闭: kernelId=" + id);
        }
        if (process == null || !process.isAlive()) {
            stateRef.set(KernelState.ERROR);
            throw new IllegalStateException(langName + " 内核进程已终止: kernelId=" + id);
        }
    }

    /** 销毁进程。 */
    private void destroyProcess() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeQuietly(processStdin);
        closeQuietly(processStdout);
    }

    /** 清理临时脚本文件。 */
    private void cleanupTempFile() {
        if (tempScriptFile != null) {
            try {
                Files.deleteIfExists(tempScriptFile);
            } catch (IOException e) {
                log.debug("清理 {} 内核临时文件失败: path={}, error={}", langName, tempScriptFile, e.getMessage());
            }
        }
    }

    /** 截断超长输出。 */
    static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...[输出已截断]";
    }

    /** 从 Map 中安全取字符串值。 */
    private static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        return String.valueOf(val);
    }

    /** 静默关闭资源。 */
    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 静默关闭
            }
        }
    }
}
