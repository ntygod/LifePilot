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
 * JavaScript 持久内核 — 通过长驻 Node.js 进程实现跨调用状态保持。
 *
 * <p>内部启动一个 {@code node} 进程运行嵌入式 REPL 脚本，
 * 使用 {@code vm.createContext()} 进行状态隔离。
 * 通过 stdin/stdout 进行行级 JSON 通信。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public final class JavaScriptKernel implements PersistentKernel {

    private static final Logger log = LoggerFactory.getLogger(JavaScriptKernel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * 嵌入式 Node.js REPL 脚本。
     *
     * <p>使用 {@code vm.createContext()} 隔离执行上下文，
     * 通过 stdin 逐行读取 JSON 请求，stdout 逐行返回 JSON 响应。</p>
     */
    private static final String NODE_KERNEL_SCRIPT = """
            const vm = require('vm');
            const readline = require('readline');

            let ctx = vm.createContext({ console: console, require: require, process: process });

            const rl = readline.createInterface({ input: process.stdin, terminal: false });

            rl.on('line', (line) => {
                let res;
                try {
                    const req = JSON.parse(line.trim());
                    const action = req.action || 'execute';

                    if (action === 'execute') {
                        const code = req.code || '';
                        let stdout = '', stderr = '';
                        const origLog = console.log;
                        const origErr = console.error;
                        console.log = (...args) => { stdout += args.map(String).join(' ') + '\\n'; };
                        console.error = (...args) => { stderr += args.map(String).join(' ') + '\\n'; };
                        let error = null;
                        try {
                            const result = vm.runInContext(code, ctx, { timeout: 120000 });
                            if (result !== undefined) {
                                stdout += String(result) + '\\n';
                            }
                        } catch (e) {
                            error = e.stack || String(e);
                        } finally {
                            console.log = origLog;
                            console.error = origErr;
                        }
                        res = { stdout, stderr, error };
                    } else if (action === 'inspect') {
                        const variables = {};
                        for (const key of Object.getOwnPropertyNames(ctx)) {
                            if (!key.startsWith('_')) {
                                try {
                                    variables[key] = typeof ctx[key];
                                } catch (e) {
                                    variables[key] = 'unknown';
                                }
                            }
                        }
                        res = { variables };
                    } else if (action === 'reset') {
                        ctx = vm.createContext({ console: console, require: require, process: process });
                        res = { message: '已清空' };
                    } else {
                        res = { error: '未知操作: ' + action };
                    }
                } catch (e) {
                    res = { error: String(e) };
                }
                process.stdout.write(JSON.stringify(res) + '\\n');
            });
            """;

    private final String id;
    private final String nodeRuntime;
    private final int maxOutputChars;
    private final AtomicReference<KernelState> stateRef = new AtomicReference<>(KernelState.STARTING);

    private Process process;
    private BufferedWriter processStdin;
    private BufferedReader processStdout;
    private Path tempScriptFile;

    /**
     * 创建 JavaScript 持久内核。
     *
     * @param kernelId      内核唯一标识
     * @param nodeRuntime   Node.js 运行时路径（如 "node"）
     * @param maxOutputChars 输出最大字符数
     */
    public JavaScriptKernel(String kernelId, String nodeRuntime, int maxOutputChars) {
        this.id = kernelId;
        this.nodeRuntime = nodeRuntime;
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
            log.error("JavaScript 内核执行失败: kernelId={}, error={}", id, e.getMessage());
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
            log.warn("JavaScript 内核检查失败: kernelId={}, error={}", id, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @Override
    public synchronized void reset() {
        ensureAlive();

        try {
            var request = Map.of("action", "reset");
            sendRequest(request, 10);
            log.info("JavaScript 内核已重置: kernelId={}", id);
        } catch (Exception e) {
            log.warn("JavaScript 内核重置失败: kernelId={}, error={}", id, e.getMessage());
        }
    }

    @Override
    public void close() {
        stateRef.set(KernelState.CLOSED);
        destroyProcess();
        cleanupTempFile();
        log.info("JavaScript 内核已关闭: kernelId={}", id);
    }

    // ─────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────

    /**
     * 启动 Node.js 进程。
     */
    private void startProcess() {
        try {
            tempScriptFile = Files.createTempFile("zhiwei-js-kernel-", ".js");
            Files.writeString(tempScriptFile, NODE_KERNEL_SCRIPT, StandardCharsets.UTF_8);
            tempScriptFile.toFile().deleteOnExit();

            var pb = new ProcessBuilder(nodeRuntime, tempScriptFile.toString());
            pb.redirectErrorStream(false);
            process = pb.start();

            processStdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            processStdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            stateRef.set(KernelState.READY);
            log.info("JavaScript 内核已启动: kernelId={}, pid={}", id, process.pid());
        } catch (IOException e) {
            stateRef.set(KernelState.ERROR);
            log.error("JavaScript 内核启动失败: kernelId={}, error={}", id, e.getMessage());
            throw new IllegalStateException("JavaScript 内核启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 向内核进程发送 JSON 请求并读取 JSON 响应。
     */
    private Map<String, Object> sendRequest(Map<String, ?> request, int timeoutSeconds) throws Exception {
        String jsonLine = MAPPER.writeValueAsString(request);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> responseFuture = executor.submit(() -> {
                processStdin.write(jsonLine);
                processStdin.newLine();
                processStdin.flush();
                return processStdout.readLine();
            });

            String responseLine = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);

            if (responseLine == null) {
                throw new IOException("JavaScript 内核进程已终止（stdin 关闭）");
            }

            return MAPPER.readValue(responseLine, MAP_TYPE);
        } catch (TimeoutException e) {
            throw new IOException("JavaScript 内核执行超时: " + timeoutSeconds + " 秒", e);
        } catch (ExecutionException e) {
            throw new IOException("JavaScript 内核通信异常: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * 检查进程是否存活。
     */
    private void ensureAlive() {
        KernelState currentState = stateRef.get();
        if (currentState == KernelState.CLOSED) {
            throw new IllegalStateException("JavaScript 内核已关闭: kernelId=" + id);
        }
        if (process == null || !process.isAlive()) {
            stateRef.set(KernelState.ERROR);
            throw new IllegalStateException("JavaScript 内核进程已终止: kernelId=" + id);
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
                log.debug("清理 JavaScript 内核临时文件失败: path={}, error={}", tempScriptFile, e.getMessage());
            }
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...[输出已截断]";
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        return String.valueOf(val);
    }

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
