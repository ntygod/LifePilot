package com.lifepilot.interaction.web.config;

import com.lifepilot.knowledge.config.KnowledgeAutoConfiguration;
import com.lifepilot.mcp.config.McpAutoConfiguration;
import com.lifepilot.observability.config.ObservabilityAutoConfiguration;
import com.lifepilot.skill.config.SkillAutoConfiguration;
import com.lifepilot.tool.config.ToolAutoConfiguration;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.web.adapter.WebChannelAdapter;
import com.lifepilot.interaction.web.controller.*;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.config.WorkflowAutoConfiguration;
import com.lifepilot.workflow.engine.WorkflowEngine;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Objects;

/**
 * Web 模块自动配置。
 *
 * <p>在 {@code lifepilot.gateway.channels.web.enabled=true} 时注册所有 Web 相关 Bean：
 * {@link WebChannelAdapter}、{@link SseSessionManager}、{@link ChatController}、
 * {@link SettingsController}、{@link WebExceptionHandler} 和 CORS 配置。</p>
 *
 * <p>通过 {@link ApplicationReadyEvent} 启动 SSE 心跳调度，
 * 通过 {@link jakarta.annotation.PreDestroy} 在应用关闭时停止心跳并清理 SseEmitter。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@AutoConfiguration(after = {
        ToolAutoConfiguration.class,
        McpAutoConfiguration.class,
        SkillAutoConfiguration.class,
        KnowledgeAutoConfiguration.class,
        WorkflowAutoConfiguration.class,
        ObservabilityAutoConfiguration.class
})
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
@EnableConfigurationProperties(WebProperties.class)
public class WebAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebAutoConfiguration.class);

    @Bean
    public WebChannelAdapter webChannelAdapter(MessageGateway gateway,
                                                GatewayProperties gatewayProperties) {
        log.info("注册 WebChannelAdapter");
        return new WebChannelAdapter(gateway, gatewayProperties);
    }

    @Bean
    public SseSessionManager sseSessionManager(WebProperties properties) {
        log.info("注册 SseSessionManager");
        return new SseSessionManager(properties);
    }

    @Bean
    public ChatController chatController(WebChannelAdapter adapter,
                                          SseSessionManager sseManager) {
        log.info("注册 ChatController");
        return new ChatController(adapter, sseManager);
    }

    @Bean
    public SettingsController settingsController() {
        log.info("注册 SettingsController");
        return new SettingsController();
    }

    @Bean
    public WebExceptionHandler webExceptionHandler() {
        log.info("注册 WebExceptionHandler");
        return new WebExceptionHandler();
    }

    // ── 模块 19 新增 Bean ──────────────────────────────────

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.knowledge", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public KnowledgeBaseController knowledgeBaseController(KnowledgeBaseManager kbManager,
                                                           @Nullable DocumentIngester documentIngester) {
        log.info("注册 KnowledgeBaseController");
        return new KnowledgeBaseController(kbManager, documentIngester);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.skills", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SkillController skillController(SkillRegistry skillRegistry,
                                           McpServerRegistry mcpServerRegistry,
                                           DynamicToolRegistry toolRegistry) {
        log.info("注册 SkillController");
        return new SkillController(skillRegistry, mcpServerRegistry, toolRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public TraceController traceController(TraceQuery traceQuery) {
        log.info("注册 TraceController");
        return new TraceController(traceQuery);
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.workflow.enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowController workflowController(WorkflowRegistry workflowRegistry,
                                                  WorkflowEngine workflowEngine,
                                                  WorkflowRepository workflowRepository) {
        log.info("注册 WorkflowController");
        return new WorkflowController(workflowRegistry, workflowEngine, workflowRepository);
    }

    /**
     * 注册 CORS 配置。
     *
     * <p>从 {@link WebProperties} 读取允许的跨域源列表。
     * 空 allowedOrigins 时不注册 CORS 映射（拒绝所有跨域请求）。</p>
     *
     * @param properties Web 配置属性
     * @return WebMvcConfigurer 实例
     */
    @Bean
    public WebMvcConfigurer corsConfigurer(WebProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {
                var cors = properties.cors();
                if (cors.allowedOrigins() == null || cors.allowedOrigins().isEmpty()) {
                    log.info("CORS allowedOrigins 为空，拒绝所有跨域请求");
                    return;
                }
                var origins = Objects.requireNonNull(cors.allowedOrigins().toArray(new String[0]));
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(cors.allowCredentials());
                log.info("CORS 配置已注册: allowedOrigins={}, allowCredentials={}",
                        cors.allowedOrigins(), cors.allowCredentials());
            }
        };
    }

    /**
     * 应用就绪后启动 SSE 心跳调度。
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        if (ctx.containsBean("sseSessionManager")) {
            var sseManager = ctx.getBean(SseSessionManager.class);
            sseManager.startHeartbeat();
            log.info("SSE 心跳调度已通过 ApplicationReadyEvent 启动");
        }
    }

    /**
     * 应用关闭时停止 SSE 心跳并清理所有连接。
     *
     * @param sseManager SSE 会话管理器
     * @return DisposableBean 用于关闭时回调
     */
    @Bean
    public SseSessionManagerShutdownHook sseSessionManagerShutdownHook(SseSessionManager sseManager) {
        return new SseSessionManagerShutdownHook(sseManager);
    }

    /**
     * SseSessionManager 关闭钩子，在 Spring 容器销毁时调用 shutdown()。
     */
    static class SseSessionManagerShutdownHook implements org.springframework.beans.factory.DisposableBean {

        private final SseSessionManager sseManager;

        SseSessionManagerShutdownHook(SseSessionManager sseManager) {
            this.sseManager = sseManager;
        }

        @Override
        public void destroy() {
            log.info("应用关闭，停止 SseSessionManager");
            sseManager.shutdown();
        }

        private static final Logger log = LoggerFactory.getLogger(SseSessionManagerShutdownHook.class);
    }
}
