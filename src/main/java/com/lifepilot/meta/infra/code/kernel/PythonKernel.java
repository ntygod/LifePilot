package com.lifepilot.meta.infra.code.kernel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Python 持久内核 — 通过长驻 python3 进程实现跨调用状态保持。
 *
 * <p>内部启动一个 {@code python3 -u} 进程运行嵌入式 REPL 脚本，
 * 通过 stdin/stdout 进行行级 JSON 通信。支持 execute、inspect、reset 三种操作，
 * 以及 {@code %pip} 魔法命令安装包。</p>
 *
 * <p>线程安全：使用 {@link AtomicReference} 管理状态，
 * 执行操作通过 synchronized 保证串行。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public final class PythonKernel implements PersistentKernel {

    private static final Logger log = LoggerFactory.getLogger(PythonKernel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * 嵌入式 Python REPL 脚本 — 部署时无需外部文件。
     *
     * <p>通信协议：每行一个 JSON 请求，返回每行一个 JSON 响应。
     * 支持 execute / inspect / reset 三种 action。</p>
     */
    private static final String PYTHON_KERNEL_SCRIPT = """
            import sys, json, io, traceback, contextlib
            _g = {"__builtins__": __builtins__}
            for line in sys.stdin:
                try:
                    req = json.loads(line.strip())
                    action = req.get("action", "execute")
                    if action == "execute":
                        code = req.get("code", "")
                        if code.strip().startswith("%pip"):
                            import subprocess
                            parts = code.strip().split(None, 2)
                            cmd = ["pip"] + parts[1:]
                            r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
                            res = {"stdout": r.stdout, "stderr": r.stderr, "error": None if r.returncode == 0 else r.stderr}
                        else:
                            so, se = io.StringIO(), io.StringIO()
                            err = None
                            try:
                                with contextlib.redirect_stdout(so), contextlib.redirect_stderr(se):
                                    exec(compile(code, "<kernel>", "exec"), _g)
                            except Exception:
                                err = traceback.format_exc()
                            res = {"stdout": so.getvalue(), "stderr": se.getvalue(), "error": err}
                    elif action == "inspect":
                        variables = {}
                        for k, v in _g.items():
                            if not k.startswith("_"):
                                try:
                                    variables[k] = type(v).__name__
                                except Exception:
                                    variables[k] = "unknown"
                        res = {"variables": variables}
                    elif action == "reset":
                        _g.clear()
                        _g["__builtins__"] = __builtins__
                        res = {"message": "已清空"}
                    else:
                        res = {"error": "未知操作: " + action}
                except Exception as e:
                    res = {"error": str(e)}
                sys.stdout.write(json.dumps(res, ensure_ascii=False) + "\\n")
                sys.stdout.flush()
            """;

    private final String id;
    private final String pythonRuntime;
    private final int maxOutputChars;
    private final AtomicReference<KernelState> stateRef = new AtomicReference<>(KernelState.STARTING);

    private Process process;
    private BufferedWriter processStdin;
    private BufferedReader processStdout;
    private Path tempScriptFile;

    /**
     * 创建 Python 持久内核。
     *
     * @param kernelId      内核唯一标识
     * @param pythonRuntime Python 运行时路径（如 "python3"）
     * @param maxOutputChars 输出最大字符数
     */
    public PythonKernel(String kernelId, String pythonRuntime, int maxOutputChars) {
        this.id = kernelId;
        this.pythonRuntime = pythonRuntime;
        this.maxOutputChars = maxOutputChars;
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
            log.error("Python 内核执行失败: kernelId={}, error={}", id, e.getMessage());
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
            log.warn("Python 内核检查失败: kernelId={}, error={}", id, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @Override
    public synchronized void reset() {
        ensureAlive();

        try {
            var request = Map.of("action", "reset");
            sendRequest(request, 10);
            log.info("Python 内核已重置: kernelId={}", id);
        } catch (Exception e) {
            log.warn("Python 内核重置失败: kernelId={}, error={}", id, e.getMessage());
        }
    }

    @Override
    public void close() {
        stateRef.set(KernelState.CLOSED);
        destroyProcess();
        cleanupTempFile();
        log.info("Python 内核已关闭: kernelId={}", id);
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    /**
     * 启动 Python 进程。
     */
    private void startProcess() {
        try {
            // 将 REPL 脚本写入临时文件
            tempScriptFile = Files.createTempFile("zhiwei-python-kernel-", ".py");
            Files.writeString(tempScriptFile, PYTHON_KERNEL_SCRIPT, StandardCharsets.UTF_8);
            tempScriptFile.toFile().deleteOnExit();

            var pb = new ProcessBuilder(pythonRuntime, "-u", tempScriptFile.toString());
            pb.redirectErrorStream(false);
            process = pb.start();

            processStdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            processStdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            stateRef.set(KernelState.READY);
            log.info("Python 内核已启动: kernelId={}, pid={}", id, process.pid());
        } catch (IOException e) {
            stateRef.set(KernelState.ERROR);
            log.error("Python 内核启动失败: kernelId={}, error={}", id, e.getMessage());
            throw new IllegalStateException("Python 内核启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 向内核进程发送 JSON 请求并读取 JSON 响应。
     *
     * @param request        请求 Map
     * @param timeoutSeconds 超时时间（秒）
     * @return 响应 Map
     * @throws Exception 通信失败或超时时
     */
    private Map<String, Object> sendRequest(Map<String, ?> request, int timeoutSeconds) throws Exception {
        String jsonLine = MAPPER.writeValueAsString(request);

        // 使用虚拟线程 + Future.get(timeout) 实现超时控制
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> responseFuture = executor.submit(() -> {
                processStdin.write(jsonLine);
                processStdin.newLine();
                processStdin.flush();
                return processStdout.readLine();
            });

            String responseLine = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);

            if (responseLine == null) {
                throw new IOException("Python 内核进程已终止（stdin 关闭）");
            }

            return MAPPER.readValue(responseLine, MAP_TYPE);
        } catch (TimeoutException e) {
            throw new IOException("Python 内核执行超时: " + timeoutSeconds + " 秒", e);
        } catch (ExecutionException e) {
            throw new IOException("Python 内核通信异常: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * 检查进程是否存活，不存活则抛出异常。
     */
    private void ensureAlive() {
        KernelState currentState = stateRef.get();
        if (currentState == KernelState.CLOSED) {
            throw new IllegalStateException("Python 内核已关闭: kernelId=" + id);
        }
        if (process == null || !process.isAlive()) {
            stateRef.set(KernelState.ERROR);
            throw new IllegalStateException("Python 内核进程已终止: kernelId=" + id);
        }
    }

    /**
     * 销毁进程。
     */
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

    /**
     * 清理临时脚本文件。
     */
    private void cleanupTempFile() {
        if (tempScriptFile != null) {
            try {
                Files.deleteIfExists(tempScriptFile);
            } catch (IOException e) {
                log.debug("清理 Python 内核临时文件失败: path={}, error={}", tempScriptFile, e.getMessage());
            }
        }
    }

    /**
     * 截断超长输出。
     */
    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...[输出已截断]";
    }

    /**
     * 从 Map 中安全取字符串值。
     */
    private static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        return String.valueOf(val);
    }

    /**
     * 静默关闭资源。
     */
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
