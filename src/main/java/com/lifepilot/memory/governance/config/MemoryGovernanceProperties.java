package com.lifepilot.memory.governance.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆治理层配置属性。
 *
 * <p>绑定 {@code lifepilot.memory.governance} 配置前缀。Phase A 阶段与旧
 * {@code MemoryProperties} 并存，后续 Phase D 删除旧配置后独立生效。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.memory.governance")
public class MemoryGovernanceProperties {

    // ─── Security 安全配置 ───

    /** 记忆安全配置。 */
    private Security security = new Security();

    // ─── McpServer 配置 ───

    /** 记忆 MCP Server 配置。 */
    private McpServer mcpServer = new McpServer();

    /**
     * 记忆安全配置 — 控制 prompt injection / trust-outlier 检测。
     */
    @Setter
    @Getter
    public static class Security {

        /** 注入检测总开关，默认启用。 */
        private boolean injectionDetectionEnabled = true;

        /** Mahalanobis 距离异常阈值。 */
        private float outlierThreshold = 3.0f;

        /** 每 space 样本窗口大小。 */
        private int sampleWindowSize = 1000;

        /** true 时 SUSPICIOUS 也当作 BLOCKED 处理。 */
        private boolean blockOnSuspicious = false;
    }

    /**
     * 记忆 MCP Server 配置 — 把知微记忆暴露为 MCP server 供外部 Agent 调用。
     */
    @Setter
    @Getter
    public static class McpServer {

        /** 总开关，默认启用。 */
        private boolean enabled = true;

        /** Server 名称。 */
        private String serverName = "zhiwei-memory";

        /** Server 版本。 */
        private String serverVersion = "1.0.0";
    }
}
