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
    public com.lifepilot.tool.validation.ToolValidator toolValidator() {
        return new com.lifepilot.tool.validation.ToolValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public BuiltinToolRegistrar builtinToolRegistrar(
            List<BuiltinTool> builtinTools,
            DynamicToolRegistry registry,
            com.lifepilot.tool.validation.ToolValidator validator) {
        return new BuiltinToolRegistrar(builtinTools, registry, validator);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToolProvider agentToolProvider(
            DynamicToolRegistry toolRegistry,
            ToolExecutionPipeline pipeline,
            ObjectMapper objectMapper,
            @Nullable MetaProperties metaProperties,
            com.lifepilot.tool.tier1.Tier1Service tier1Service) {
        log.info("工具桥接层初始化: 注册 ToolBridge 实现的 AgentToolProvider");
        int maxToolOutputChars = metaProperties != null
                ? metaProperties.getInfra().getMaxToolOutputChars()
                : new MetaProperties().getInfra().getMaxToolOutputChars();
        return new ToolBridgeAgentToolProvider(
                toolRegistry, pipeline, objectMapper, maxToolOutputChars, tier1Service);
    }

    // ==== 搜索服务 / Tier1 / Meta BuiltinTool 基础件注册 ====

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.ToolSearchQuerySanitizer toolSearchQuerySanitizer() {
        return new com.lifepilot.tool.search.ToolSearchQuerySanitizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.cache.SchemaCache schemaCache(ToolConfigProperties p) {
        return new com.lifepilot.tool.search.cache.SchemaCache(p.getSearch().getCache().getLayerAMaxSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.cache.SearchResultCache searchResultCache(ToolConfigProperties p) {
        return new com.lifepilot.tool.search.cache.SearchResultCache(
                p.getSearch().getCache().getLayerBMaxSize(),
                java.time.Duration.ofMinutes(p.getSearch().getCache().getLayerBTtlMinutes()));
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.cache.SessionSearchMemo sessionSearchMemo() {
        return new com.lifepilot.tool.search.cache.SessionSearchMemo();
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.tier1.Tier1AdvisoryRepository tier1AdvisoryRepository(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new com.lifepilot.tool.tier1.Tier1AdvisoryRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.tier1.Tier1Service tier1Service(
            ToolConfigProperties p,
            com.lifepilot.tool.tier1.Tier1AdvisoryRepository repo) {
        return new com.lifepilot.tool.tier1.Tier1Service(p, repo);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.ToolSearchIndexBuilder toolSearchIndexBuilder(
            DynamicToolRegistry registry,
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new com.lifepilot.tool.search.ToolSearchIndexBuilder(registry, jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.ToolSearchIndexMaintainer toolSearchIndexMaintainer(
            com.lifepilot.tool.search.ToolSearchIndexBuilder builder,
            DynamicToolRegistry registry,
            com.lifepilot.tool.search.cache.SchemaCache schemaCache,
            com.lifepilot.tool.search.cache.SearchResultCache searchCache) {
        return new com.lifepilot.tool.search.ToolSearchIndexMaintainer(builder, registry, schemaCache, searchCache);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.ToolSearchService toolSearchService(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            DynamicToolRegistry registry,
            com.lifepilot.tool.search.ToolSearchQuerySanitizer sanitizer,
            com.lifepilot.tool.tier1.Tier1Service tier1,
            com.lifepilot.tool.search.cache.SearchResultCache searchCache,
            com.lifepilot.tool.search.cache.SessionSearchMemo memo,
            ToolConfigProperties properties) {
        return new com.lifepilot.tool.search.ToolSearchService(
                jdbcTemplate, registry, sanitizer, tier1, searchCache, memo, properties.getSearch());
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.ToolDescribeService toolDescribeService(
            DynamicToolRegistry registry,
            com.lifepilot.tool.search.cache.SchemaCache schemaCache,
            ToolConfigProperties properties) {
        return new com.lifepilot.tool.search.ToolDescribeService(
                registry, schemaCache, properties.getDescribe().getMaxBatchSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.ToolListService toolListService(DynamicToolRegistry registry) {
        return new com.lifepilot.tool.search.ToolListService(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.tool.search.BuiltinToolSearchProvider builtinToolSearchProvider(
            com.lifepilot.tool.search.ToolSearchService search,
            com.lifepilot.tool.search.ToolDescribeService describe,
            com.lifepilot.tool.search.ToolListService list) {
        return new com.lifepilot.tool.search.BuiltinToolSearchProvider(search, describe, list);
    }

    // 三个 Meta BuiltinTool 暴露成 Bean，会被 BuiltinToolRegistrar 自动扫描注册
    @Bean
    public com.lifepilot.tool.BuiltinTool toolsSearchBuiltin(
            com.lifepilot.tool.search.BuiltinToolSearchProvider provider) {
        return provider.searchTool();
    }

    @Bean
    public com.lifepilot.tool.BuiltinTool toolsDescribeBuiltin(
            com.lifepilot.tool.search.BuiltinToolSearchProvider provider) {
        return provider.describeTool();
    }

    @Bean
    public com.lifepilot.tool.BuiltinTool toolsListBuiltin(
            com.lifepilot.tool.search.BuiltinToolSearchProvider provider) {
        return provider.listTool();
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
