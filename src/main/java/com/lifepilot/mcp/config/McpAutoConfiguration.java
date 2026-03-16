package com.lifepilot.mcp.config;

import com.lifepilot.mcp.adapter.McpToolAdapter;
import com.lifepilot.mcp.bridge.SkillToMcpBridge;
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
 * 应用启动完成后自动初始化所有配置的 MCP Server 连接。</p>
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
    public McpServerRegistry mcpServerRegistry(
            McpToolAdapter mcpToolAdapter,
            DynamicToolRegistry dynamicToolRegistry,
            ApplicationEventPublisher eventPublisher) {
        return new McpServerRegistry(mcpToolAdapter, dynamicToolRegistry, eventPublisher);
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
     * 应用启动完成后初始化所有配置的 MCP Server 连接。
     *
     * <p>当 discovery.enabled=true 时，通过 McpServerDiscovery 合并显式配置与自动发现的配置；
     * 否则仅使用显式配置。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var registry = event.getApplicationContext().getBean(McpServerRegistry.class);
        var discovery = event.getApplicationContext().getBean(McpServerDiscovery.class);

        var configs = discovery.discover();
        if (!configs.isEmpty()) {
            log.info("MCP 自动配置: 初始化 {} 个 MCP Server（含自动发现）", configs.size());
            registry.initializeAll(configs);
        } else {
            log.info("MCP 自动配置: 无 MCP Server 配置");
        }
    }
}
