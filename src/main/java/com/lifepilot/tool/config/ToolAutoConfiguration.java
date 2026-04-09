package com.lifepilot.tool.config;

import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.mcp.adapter.McpToolExecutor;
import com.lifepilot.permission.service.NoopPermissionApprovalService;
import com.lifepilot.permission.service.PermissionApprovalService;
import com.lifepilot.permission.service.PermissionRequestFactory;
import com.lifepilot.permission.service.PermissionService;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.McpTool;
import com.lifepilot.tool.bridge.ToolBridgeAgentToolProvider;
import com.lifepilot.tool.pipeline.IdempotencyManager;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.BuiltinToolRegistrar;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 工具系统 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.tool.enabled=true}（默认）激活，
 * 注册所有工具系统核心 Bean。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@AutoConfiguration
@EnableConfigurationProperties(ToolConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.tool", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ToolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyManager idempotencyManager() {
        return new IdempotencyManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public DynamicToolRegistry dynamicToolRegistry(
            ApplicationEventPublisher eventPublisher) {
        return new DynamicToolRegistry(eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionApprovalService permissionApprovalService() {
        return new NoopPermissionApprovalService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutionPipeline toolExecutionPipeline(
            DynamicToolRegistry toolRegistry,
            GuardrailEngine guardrailEngine,
            IdempotencyManager idempotencyManager,
            PermissionService permissionService,
            PermissionRequestFactory permissionRequestFactory,
            PermissionApprovalService permissionApprovalService,
            ToolConfigProperties config) {
        var p = config.getPipeline();
        log.info("工具执行管线初始化: timeout={}s, maxRetries={}, retryDelay={}ms",
                p.getDefaultTimeoutSeconds(), p.getDefaultMaxRetries(), p.getRetryInitialDelayMs());
        return new ToolExecutionPipeline(
                toolRegistry, guardrailEngine, idempotencyManager,
                permissionService, permissionRequestFactory, permissionApprovalService,
                p.getRetryInitialDelayMs(), p.getRetryMultiplier(), p.getRetryMaxDelayMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public BuiltinToolRegistrar builtinToolRegistrar(
            List<BuiltinTool> builtinTools,
            DynamicToolRegistry registry) {
        return new BuiltinToolRegistrar(builtinTools, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToolProvider agentToolProvider(
            DynamicToolRegistry toolRegistry,
            ToolExecutionPipeline pipeline,
            ObjectMapper objectMapper,
            @Nullable MetaProperties metaProperties) {
        log.info("工具桥接层初始化: 注册 ToolBridge 实现的 AgentToolProvider");
        int maxToolOutputChars = metaProperties != null
                ? metaProperties.getInfra().getMaxToolOutputChars()
                : new MetaProperties().getInfra().getMaxToolOutputChars();
        return new ToolBridgeAgentToolProvider(
                toolRegistry, pipeline, objectMapper, maxToolOutputChars);
    }

    /**
     * 初始化 McpTool 的 McpToolExecutor 引用。
     *
     * <p>McpToolExecutor 为可选依赖，MCP 模块未启用时跳过注入。</p>
     */
    @Bean
    public InitializingBean mcpToolExecutorInitializer(@Nullable McpToolExecutor mcpToolExecutor) {
        return () -> {
            if (mcpToolExecutor != null) {
                McpTool.setMcpToolExecutor(mcpToolExecutor);
                log.info("McpToolExecutor 已注入 McpTool");
            } else {
                log.info("McpToolExecutor 不可用，McpTool.execute() 将返回错误");
            }
        };
    }
}
