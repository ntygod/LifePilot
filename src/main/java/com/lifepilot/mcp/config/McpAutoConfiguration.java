package com.lifepilot.mcp.config;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.mcp.adapter.McpToolAdapter;
import com.lifepilot.mcp.adapter.McpToolExecutor;
import com.lifepilot.mcp.bridge.SkillToMcpBridge;
import com.lifepilot.mcp.cache.McpToolManifestCache;
import com.lifepilot.mcp.discovery.McpServerDiscovery;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

/**
 * MCP 协议支持自动配置。
 *
 * <p>通过 {@code lifepilot.mcp.enabled=true}（默认）激活，
 * 注册 McpToolAdapter、McpServerRegistry、SkillToMcpBridge Bean。
 * 应用启动时仅注册 MCP Server 配置，所有连接由懒连接按需触发。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@AutoConfiguration
@EnableConfigurationProperties(McpConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.mcp", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class McpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public McpToolAdapter mcpToolAdapter() {
        return new McpToolAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    public McpToolManifestCache mcpToolManifestCache() {
        return new McpToolManifestCache();
    }

    @Bean
    @ConditionalOnMissingBean
    public McpServerRegistry mcpServerRegistry(
            McpToolAdapter mcpToolAdapter,
            DynamicToolRegistry dynamicToolRegistry,
            ApplicationEventPublisher eventPublisher,
            SharedScheduler sharedScheduler,
            McpToolManifestCache mcpToolManifestCache) {
        return new McpServerRegistry(mcpToolAdapter, dynamicToolRegistry,
                eventPublisher, sharedScheduler, mcpToolManifestCache);
    }

    @Bean
    @ConditionalOnMissingBean
    public McpToolExecutor mcpToolExecutor(McpServerRegistry mcpServerRegistry) {
        return new McpToolExecutor(mcpServerRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillToMcpBridge skillToMcpBridge(DynamicToolRegistry dynamicToolRegistry) {
        return new SkillToMcpBridge(dynamicToolRegistry);
    }

    /**
     * MCP 服务器自动发现组件。
     *
     * <p>扫描本地配置文件（~/.mcp/servers.json、.mcp.json 等）自动注册 MCP 服务器，
     * 与显式配置合并后返回完整的服务器列表。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public McpServerDiscovery mcpServerDiscovery(McpConfigProperties properties) {
        return new McpServerDiscovery(properties);
    }

    /**
     * 应用启动完成后注册所有 MCP Server 配置（仅注册，不连接）。
     *
     * <p>所有连接由懒连接触发：Agent 调用某个 MCP 工具时才按需连接对应的 Server。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var registry = event.getApplicationContext().getBean(McpServerRegistry.class);
        var discovery = event.getApplicationContext().getBean(McpServerDiscovery.class);

        var configs = discovery.discover();
        if (!configs.isEmpty()) {
            log.info("MCP 自动配置: 注册 {} 个 MCP Server（懒连接模式，按需连接）", configs.size());
            registry.initializeAll(configs);
            registry.discoverUncached();
        } else {
            log.info("MCP 自动配置: 无 MCP Server 配置");
        }
    }
}
