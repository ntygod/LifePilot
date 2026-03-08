package com.lifepilot.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.exception.McpToolCallException;
import com.lifepilot.mcp.exception.McpTransportException;
import com.lifepilot.mcp.protocol.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio 传输实现。
 *
 * <p>通过 ProcessBuilder 启动 MCP Server 子进程，
 * 使用标准输入（stdin）发送请求，标准输出（stdout）接收响应。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public final class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerConfig config;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pendingRequests
            = new ConcurrentHashMap<>();

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;

    public StdioTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("启动 MCP Server 子进程: command={}, args={}",
                        config.command(), config.args());

                var command = buildCommand(config.command(), config.args());

                var pb = new ProcessBuilder(command);
                if (!config.env().isEmpty()) {
                    pb.environment().putAll(config.env());
                }
                pb.redirectErrorStream(false);
                process = pb.start();

                stdin = new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream()));
                stdout = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));

                // Virtual Thread 读取 stdout
                Thread.ofVirtual()
                        .name("mcp-stdio-reader-" + config.name())
                        .start(this::readLoop);

                // Virtual Thread 读取 stderr
                Thread.ofVirtual()
                        .name("mcp-stdio-stderr-" + config.name())
                        .start(this::stderrLoop);

                connected.set(true);
                log.info("MCP Server 子进程启动成功: name={}, pid={}",
                        config.name(), process.pid());

            } catch (IOException e) {
                throw new McpTransportException(
                        "MCP Server 子进程启动失败: " + config.name(), e);
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public CompletableFuture<JsonNode> sendRequest(String method, Map<String, Object> params) {
        if (!connected.get()) {
            return CompletableFuture.failedFuture(
                    new McpTransportException("传输未连接: " + config.name()));
        }

        long id = requestIdCounter.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pendingRequests.put(id, future);

        try {
            var message = JsonRpcMessage.request(id, method, params);
            String json = MAPPER.writeValueAsString(message);

            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }

            log.debug("JSON-RPC 请求已发送: id={}, method={}, server={}",
                    id, method, config.name());

        } catch (IOException e) {
            pendingRequests.remove(id);
            future.completeExceptionally(
                    new McpTransportException("请求发送失败: " + method, e));
        }

        return future;
    }

    @Override
    public void sendNotification(String method, Map<String, Object> params) {
        if (!connected.get()) {
            log.warn("传输未连接，通知丢弃: method={}, server={}", method, config.name());
            return;
        }

        try {
            var message = JsonRpcMessage.notification(method, params);
            String json = MAPPER.writeValueAsString(message);

            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }

            log.debug("JSON-RPC 通知已发送: method={}, server={}", method, config.name());

        } catch (IOException e) {
            log.warn("通知发送失败: method={}, server={}, error={}",
                    method, config.name(), e.getMessage());
        }
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        return CompletableFuture.runAsync(() -> {
            connected.set(false);

            // 完成所有待处理的请求
            pendingRequests.forEach((id, future) ->
                    future.completeExceptionally(
                            new McpTransportException("传输已断开")));
            pendingRequests.clear();

            // 关闭管道
            try {
                if (stdin != null) stdin.close();
                if (stdout != null) stdout.close();
            } catch (IOException e) {
                log.warn("管道关闭异常: server={}, error={}", config.name(), e.getMessage());
            }

            // 销毁子进程
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    boolean exited = process.waitFor(5, TimeUnit.SECONDS);
                    if (!exited) {
                        log.warn("MCP Server 子进程未在 5 秒内退出，强制终止: name={}",
                                config.name());
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }

            log.info("MCP Server 连接已断开: name={}", config.name());
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public boolean isConnected() {
        return connected.get() && process != null && process.isAlive();
    }

    @Override
    public TransportType transportType() {
        return TransportType.STDIO;
    }

    /** stdout 读取循环（在 Virtual Thread 中运行）。 */
    private void readLoop() {
        try {
            String line;
            while (connected.get() && (line = stdout.readLine()) != null) {
                try {
                    JsonNode node = MAPPER.readTree(line);

                    if (node.has("id") && node.has("result")) {
                        // JSON-RPC 响应
                        long id = node.get("id").asLong();
                        var future = pendingRequests.remove(id);
                        if (future != null) {
                            future.complete(node.get("result"));
                        } else {
                            log.warn("收到未知请求 ID 的响应: id={}, server={}",
                                    id, config.name());
                        }
                    } else if (node.has("id") && node.has("error")) {
                        // JSON-RPC 错误响应
                        long id = node.get("id").asLong();
                        var future = pendingRequests.remove(id);
                        if (future != null) {
                            String errorMsg = node.get("error").get("message").asText();
                            future.completeExceptionally(new McpToolCallException(errorMsg));
                        }
                    } else if (node.has("method") && !node.has("id")) {
                        // JSON-RPC 通知（来自 Server）
                        String method = node.get("method").asText();
                        log.debug("收到 Server 通知: method={}, server={}",
                                method, config.name());
                    }
                } catch (Exception e) {
                    log.warn("JSON-RPC 消息解析失败: server={}, error={}",
                            config.name(), e.getMessage());
                }
            }
        } catch (IOException e) {
            if (connected.get()) {
                log.error("stdout 读取异常: server={}, error={}",
                        config.name(), e.getMessage());
            }
        }
        log.debug("stdout 读取循环结束: server={}", config.name());
    }

    /** stderr 读取循环（仅记录日志）。 */
    private void stderrLoop() {
        try (var stderr = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = stderr.readLine()) != null) {
                log.debug("[MCP Server stderr] {}: {}", config.name(), line);
            }
        } catch (IOException e) {
            if (connected.get()) {
                log.warn("stderr 读取异常: server={}", config.name());
            }
        }
    }

    /**
     * 构建子进程启动命令。
     *
     * <p>Windows 上 npx 会 spawn 子进程运行实际的包，npx 自身随后退出，
     * 导致 Java ProcessBuilder 持有的 stdin/stdout 管道立即断开。</p>
     *
     * <p>解决方案：
     * <ul>
     *   <li>如果命令是 npx，先通过 npx --yes 预安装包到 npx 缓存，
     *       然后扫描缓存目录找到包的 bin 入口 JS 文件，
     *       直接用 node.exe 运行该入口文件，完全绕过 npx。</li>
     *   <li>其他命令仍通过 cmd.exe /d /s /c 启动。</li>
     *   <li>非 Windows 系统直接拼接命令。</li>
     * </ul></p>
     *
     * @param cmd  原始命令（如 "npx"）
     * @param args 命令参数列表
     * @return ProcessBuilder 可用的命令列表
     */
    private static List<String> buildCommand(String cmd, List<String> args) {
        if (isWindows()) {
            if ("npx".equalsIgnoreCase(cmd) && !args.isEmpty()) {
                // npx 特殊处理：解析包的 bin 入口，直接用 node.exe 运行
                String packageName = args.getFirst();
                var resolved = resolveNpxPackageEntry(packageName);
                if (resolved != null) {
                    var command = new ArrayList<>(resolved);
                    // 跳过第一个参数（包名），追加剩余参数
                    if (args.size() > 1) {
                        command.addAll(args.subList(1, args.size()));
                    }
                    log.info("npx 直接入口模式: {}", command);
                    return command;
                }
                log.warn("无法解析 npx 包入口，回退到 cmd.exe 模式: package={}", packageName);
            }
            // 通用 Windows 命令：通过 cmd.exe /d /s /c 启动
            var sb = new StringBuilder();
            sb.append(cmd);
            for (String arg : args) {
                sb.append(' ').append(arg);
            }
            return List.of("cmd.exe", "/d", "/s", "/c", "\"" + sb + "\"");
        } else {
            var command = new ArrayList<String>();
            command.add(cmd);
            command.addAll(args);
            return command;
        }
    }

    /**
     * 解析 npx 包的实际入口文件，返回 [node.exe, entry.js] 命令列表。
     *
     * <p>流程：
     * <ol>
     *   <li>运行 {@code npx --yes <package>} 确保包已安装到 npx 缓存</li>
     *   <li>通过 {@code npm config get cache} 获取 npm 缓存根目录</li>
     *   <li>扫描 {@code {cache}/_npx/} 下所有子目录，查找目标包的 package.json</li>
     *   <li>从 package.json 的 bin 字段解析出入口 JS 文件路径</li>
     *   <li>通过 {@code where.exe node.exe} 定位 node.exe</li>
     * </ol></p>
     *
     * @param packageName npm 包名（如 "@anaisbetts/mcp-installer"）
     * @return [node.exe 路径, 入口 JS 文件绝对路径]，解析失败返回 null
     */
    private static List<String> resolveNpxPackageEntry(String packageName) {
        try {
            // 1. 预安装包到 npx 缓存（静默运行，仅等待安装完成）
            ensureNpxPackageInstalled(packageName);

            // 2. 获取 npm 缓存路径
            String cacheDir = runCommandForOutput("npm", "config", "get", "cache");
            if (cacheDir == null) return null;

            // 3. 扫描 npx 缓存找到包的 bin 入口
            var npxCacheDir = new File(cacheDir, "_npx");
            if (!npxCacheDir.isDirectory()) {
                log.debug("npx 缓存目录不存在: {}", npxCacheDir);
                return null;
            }

            String entryPath = findPackageBinEntry(npxCacheDir, packageName);
            if (entryPath == null) {
                log.debug("未在 npx 缓存中找到包入口: package={}", packageName);
                return null;
            }

            // 4. 定位 node.exe
            String nodeExe = resolveNodeExe();
            if (nodeExe == null) return null;

            return List.of(nodeExe, entryPath);

        } catch (Exception e) {
            log.debug("npx 包入口解析异常: package={}, error={}", packageName, e.getMessage());
            return null;
        }
    }

    /**
     * 确保 npx 包已安装到缓存（同步等待，最多 60 秒）。
     *
     * <p>使用 {@code npx --yes --package=<pkg> -- echo ok} 触发安装但不运行包的 bin 入口，
     * 避免 MCP Server 类型的包启动后阻塞等待 stdin。</p>
     */
    private static void ensureNpxPackageInstalled(String packageName) {
        try {
            var proc = new ProcessBuilder(
                    "cmd.exe", "/d", "/s", "/c",
                    "\"npx --yes --package=" + packageName + " -- echo ok\"")
                    .redirectErrorStream(true)
                    .start();
            // 消费输出防止阻塞
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                while (reader.readLine() != null) { /* 丢弃 */ }
            }
            boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("npx 包预安装超时（60s）: package={}", packageName);
            } else {
                log.debug("npx 包预安装完成: package={}", packageName);
            }
        } catch (Exception e) {
            log.debug("npx 包预安装异常（忽略）: package={}, error={}", packageName, e.getMessage());
        }
    }

    /**
     * 在 npx 缓存目录中查找指定包的 bin 入口文件绝对路径。
     *
     * <p>扫描 {@code npxCacheDir} 下所有子目录，查找
     * {@code node_modules/{packageName}/package.json}，
     * 从 bin 字段解析出入口 JS 文件。</p>
     */
    private static String findPackageBinEntry(File npxCacheDir, String packageName) {
        var subdirs = npxCacheDir.listFiles(File::isDirectory);
        if (subdirs == null) return null;

        for (var subdir : subdirs) {
            var pkgJsonFile = new File(subdir,
                    "node_modules" + File.separator
                            + packageName.replace('/', File.separatorChar)
                            + File.separator + "package.json");
            if (!pkgJsonFile.exists()) continue;

            try {
                var pkgJson = MAPPER.readTree(pkgJsonFile);
                var binNode = pkgJson.get("bin");
                if (binNode == null) continue;

                // bin 可以是字符串或对象
                String binRelPath;
                if (binNode.isTextual()) {
                    binRelPath = binNode.asText();
                } else if (binNode.isObject()) {
                    // 取第一个 bin 入口
                    var firstField = binNode.fields().next();
                    binRelPath = firstField.getValue().asText();
                } else {
                    continue;
                }

                // 解析为绝对路径
                var pkgDir = pkgJsonFile.getParentFile();
                var entryFile = new File(pkgDir, binRelPath.replace('/', File.separatorChar))
                        .getCanonicalFile();
                if (entryFile.exists()) {
                    log.debug("找到 npx 包入口: package={}, entry={}",
                            packageName, entryFile.getAbsolutePath());
                    return entryFile.getAbsolutePath();
                }
            } catch (IOException e) {
                log.debug("解析 package.json 失败: file={}, error={}",
                        pkgJsonFile, e.getMessage());
            }
        }
        return null;
    }

    /** 定位 node.exe 的绝对路径。 */
    private static String resolveNodeExe() {
        String path = runCommandForOutput("where.exe", "node.exe");
        if (path != null) {
            var nodeExe = new File(path);
            if (nodeExe.exists()) return nodeExe.getAbsolutePath();
        }
        return null;
    }

    /** 运行命令并返回第一行输出（去除首尾空白），失败返回 null。 */
    private static String runCommandForOutput(String... command) {
        try {
            var proc = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                output = reader.readLine();
            }
            proc.waitFor(10, TimeUnit.SECONDS);
            return (output != null && !output.isBlank()) ? output.trim() : null;
        } catch (Exception e) {
            log.debug("命令执行失败: command={}, error={}", String.join(" ", command), e.getMessage());
            return null;
        }
    }

    /** 检测当前操作系统是否为 Windows。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
