package com.lifepilot.tool.config;

import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.interaction.NoOpUserConfirmationService;
import com.lifepilot.interaction.UserConfirmationService;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.SkillTool;
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
    public UserConfirmationService userConfirmationService() {
        return new NoOpUserConfirmationService();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyManager idempotencyManager() {
        return new IdempotencyManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public DynamicToolRegistry dynamicToolRegistry(
            GuardrailEngine guardrailEngine,
            ApplicationEventPublisher eventPublisher) {
        return new DynamicToolRegistry(guardrailEngine, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutionPipeline toolExecutionPipeline(
            DynamicToolRegistry toolRegistry,
            GuardrailEngine guardrailEngine,
            IdempotencyManager idempotencyManager,
            UserConfirmationService confirmationService,
            ToolConfigProperties config) {
        var p = config.getPipeline();
        log.info("工具执行管线初始化: timeout={}s, maxRetries={}, retryDelay={}ms",
                p.getDefaultTimeoutSeconds(), p.getDefaultMaxRetries(), p.getRetryInitialDelayMs());
        return new ToolExecutionPipeline(
                toolRegistry, guardrailEngine, idempotencyManager, confirmationService,
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
            MetaProperties metaProperties,
            @Nullable TraceRecorder traceRecorder) {
        log.info("工具桥接层初始化: 注册 ToolBridge 实现的 AgentToolProvider, traceRecorder={}", traceRecorder != null ? "已注入" : "未注入");
        return new ToolBridgeAgentToolProvider(toolRegistry, pipeline, objectMapper, metaProperties, traceRecorder);
    }

    /**
     * 初始化 SkillTool 的静态 SkillActivator 引用。
     *
     * <p>SkillActivator 为可选依赖，Skill 模块未启用时跳过注入。</p>
     */
    @Bean
    public InitializingBean skillToolActivatorInitializer(@Nullable SkillActivator skillActivator) {
        return () -> {
            if (skillActivator != null) {
                SkillTool.setSkillActivator(skillActivator);
                log.info("SkillTool 静态 SkillActivator 引用已初始化");
            } else {
                log.info("SkillActivator 不可用，SkillTool.execute() 将返回错误");
            }
        };
    }
}
