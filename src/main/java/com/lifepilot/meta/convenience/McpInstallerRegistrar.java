package com.lifepilot.meta.convenience;

import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.mcp.transport.TransportType;
import com.lifepilot.meta.config.MetaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.concurrent.TimeUnit;

/**
 * 内置 mcp-installer 注册器 — 启动时检查 npx 可用性并注册 mcp-installer MCP Server。
 *
 * <p>在 {@link #afterPropertiesSet()} 中执行以下流程：</p>
 * <ol>
 *   <li>通过 {@link ProcessBuilder} 执行 {@code npx --version} 检查 npx 是否可用</li>
 *   <li>可用时，构建 {@link McpServerConfig} 并通过 {@link McpServerRegistry#connectServer} 注册</li>
 *   <li>不可用时，记录 WARN 日志并跳过注册</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class McpInstallerRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(McpInstallerRegistrar.class);

    /** mcp-installer 服务器名称。 */
    static final String SERVER_NAME = "mcp-installer";

    /** npx 可用性检查超时（秒）。 */
    private static final int NPX_CHECK_TIMEOUT_SECONDS = 10;

    private final McpServerRegistry mcpServerRegistry;
    private final MetaProperties properties;

    public McpInstallerRegistrar(McpServerRegistry mcpServerRegistry,
                                 MetaProperties properties) {
        this.mcpServerRegistry = mcpServerRegistry;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        registerMcpInstaller();
    }

    /**
     * 检查 npx 可用性并注册 mcp-installer MCP Server。
     */
    void registerMcpInstaller() {
        var mcpInstaller = properties.getMcpInstaller();

        if (!isNpxAvailable()) {
            log.warn("npx 不可用，跳过 mcp-installer 注册。请安装 Node.js 以启用 MCP Server 安装功能");
            return;
        }

        // 构建 McpServerConfig
        var config = McpServerConfig.builder()
                .name(SERVER_NAME)
                .transport(TransportType.STDIO)
                .command(mcpInstaller.getCommand())
                .args(mcpInstaller.getArgs())
                .autoConnect(true)
                .reconnect(false)
                .build();

        // 通过 McpServerRegistry 注册并连接
        mcpServerRegistry.connectServer(config);
        log.info("mcp-installer 注册成功: command={}, args={}", mcpInstaller.getCommand(), mcpInstaller.getArgs());
    }

    /**
     * 检查 npx 是否可用。
     *
     * <p>通过 {@link ProcessBuilder} 执行 {@code npx --version}，
     * 在 {@value #NPX_CHECK_TIMEOUT_SECONDS} 秒内完成且退出码为 0 则视为可用。</p>
     *
     * <p>Windows 上 npx 是 .cmd 批处理脚本，需要通过 {@code cmd /c} 执行。</p>
     *
     * @return npx 可用返回 true，否则返回 false
     */
    boolean isNpxAvailable() {
        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "npx", "--version");
            } else {
                pb = new ProcessBuilder("npx", "--version");
            }
            var process = pb.redirectErrorStream(true).start();
            boolean finished = process.waitFor(NPX_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("npx 可用性检查失败: error={}", e.getMessage());
            return false;
        }
    }

}
