package com.lifepilot.interaction.web.config;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.interaction.web.a2ui.UiEmitToolProvider;
import com.lifepilot.interaction.web.a2ui.UiEmitTreeCapture;
import com.lifepilot.interaction.web.controller.WebExceptionHandler;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.service.BrowserIngressService;
import com.lifepilot.interaction.web.service.ChatTurnService;
import com.lifepilot.interaction.web.service.WebPermissionApprovalService;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.interaction.runtime.ChannelPermissionApprovalService;
import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.permission.service.CompositePermissionApprovalService;
import com.lifepilot.permission.service.PermissionApprovalService;
import com.lifepilot.permission.service.PermissionService;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.media.audio.AudioTranscriber;
import com.lifepilot.media.config.MediaProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.lang.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 模块自动配置。
 *
 * <p>在 {@code lifepilot.gateway.channels.web.enabled=true} 时注册所有 Web 相关 Bean：
 * {@link BrowserIngressService}、
 * {@link SseSessionManager}、{@link WebExceptionHandler} 和 CORS 配置。</p>
 *
 * <p>Web Controller（如 {@link com.lifepilot.interaction.web.controller.ChatController}、
 * {@link com.lifepilot.interaction.web.controller.SettingsController} 等）通过组件扫描自动注册。
 * 如果依赖不存在，应用启动时会失败（fail-fast），这比条件注册更清晰。</p>
 *
 * <p>通过 {@link ApplicationReadyEvent} 启动 SSE 心跳调度，
 * 通过 {@link jakarta.annotation.PreDestroy} 在应用关闭时停止心跳并清理 SseEmitter。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
@EnableConfigurationProperties({WebProperties.class, A2uiProperties.class})
public class WebAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebAutoConfiguration.class);

    @Bean
    public BrowserIngressService browserIngressService(AttachmentRepository attachmentRepository,
                                                       ChatTurnService chatTurnService,
                                                       SseSessionManager sseSessionManager,
                                                       @Nullable AudioTranscriber audioTranscriber,
                                                       MediaProperties mediaProperties,
                                                       @Nullable ProviderRegistry providerRegistry) {
        log.info("注册 BrowserIngressService");
        return new BrowserIngressService(
                attachmentRepository,
                chatTurnService,
                sseSessionManager,
                audioTranscriber,
                mediaProperties,
                () -> providerRegistry != null
                        && !providerRegistry.findByCapability(ProviderCapability.NATIVE_AUDIO).isEmpty()
        );
    }

    @Bean
    public SseSessionManager sseSessionManager(WebProperties properties, SharedScheduler sharedScheduler) {
        log.info("注册 SseSessionManager");
        return new SseSessionManager(properties, sharedScheduler);
    }

    @Bean
    public WebExceptionHandler webExceptionHandler() {
        log.info("注册 WebExceptionHandler");
        return new WebExceptionHandler();
    }

    @Bean
    public WebPermissionApprovalService webPermissionApprovalService(
            SseSessionManager sseSessionManager,
            TranscriptStore transcriptStore,
            PermissionService permissionService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            ObservabilityProperties observabilityProperties) {
        long timeout = observabilityProperties.getGuardrail().getApprovalTimeoutSeconds();
        log.info("注册 WebPermissionApprovalService: timeout={}s", timeout);
        return new WebPermissionApprovalService(sseSessionManager, transcriptStore,
                permissionService, objectMapper, timeout);
    }

    /**
     * 路由审批服务 — 根据 approvalContext 中的渠道标识直接路由。
     * 有 streamId → Web；有 channelInstanceId → Channel。
     */
    @Bean
    @Primary
    public PermissionApprovalService compositePermissionApprovalService(
            WebPermissionApprovalService webApprovalService,
            @Nullable ChannelPermissionApprovalService channelApprovalService) {
        return new CompositePermissionApprovalService(webApprovalService, channelApprovalService);
    }

    /**
     * 注册 A2UI 组件树捕获桥接器，在 ui.emit 工具执行器和编排器之间传递组件树用于持久化。
     *
     * @return UiEmitTreeCapture 实例
     */
    @Bean
    public UiEmitTreeCapture uiEmitTreeCapture() {
        log.info("注册 UiEmitTreeCapture");
        return new UiEmitTreeCapture();
    }

    /**
     * 在 ApplicationReadyEvent 中注册 ui.emit 工具到 DynamicToolRegistry。
     *
     * <p>与 MetaAutoConfiguration.registerTools() 使用相同的 HIGHEST_PRECEDENCE 优先级，
     * 确保在 SkillAutoConfiguration 加载 Skill（校验 suggested-tools）之前完成注册。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void registerUiEmitTool(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        if (!ctx.containsBean("sseSessionManager")) {
            return;
        }
        var sseManager = ctx.getBean(SseSessionManager.class);
        var a2uiProperties = ctx.getBean(A2uiProperties.class);
        var treeCapture = ctx.getBean(UiEmitTreeCapture.class);
        var toolRegistry = ctx.getBean(com.lifepilot.tool.registry.DynamicToolRegistry.class);

        var tool = new UiEmitToolProvider(sseManager, a2uiProperties.maxComponentsPerTree(), treeCapture).buildTool();
        toolRegistry.registerBuiltinTool(tool);
        log.info("注册 ui.emit 内置工具: maxComponentsPerTree={}", a2uiProperties.maxComponentsPerTree());
    }

    // 注意：ChatController、SettingsController、KnowledgeBaseController、SkillController、
    // TraceController、WorkflowController 现在通过组件扫描自动注册，不再需要手动注册。
    // 如果依赖不存在，应用启动时会失败（fail-fast），这比条件注册更清晰。

    /**
     * 注册 CORS 配置和静态资源排除规则。
     *
     * <p>从 {@link WebProperties} 读取允许的跨域源列表。
     * 空 allowedOrigins 时不注册 CORS 映射（拒绝所有跨域请求）。</p>
     *
     * <p>同时配置静态资源处理器，确保 {@code /api/**} 路径不会被静态资源处理器处理，
     * 避免在控制器未注册时出现 "No static resource" 错误。</p>
     *
     * @param properties Web 配置属性
     * @return WebMvcConfigurer 实例
     */
    @Bean
    public WebMvcConfigurer webMvcConfigurer(WebProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {
                var cors = properties.cors();
                if (cors.allowedOrigins() == null || cors.allowedOrigins().isEmpty()) {
                    log.info("CORS allowedOrigins 为空，拒绝所有跨域请求");
                    return;
                }
                var origins = cors.allowedOrigins().toArray(new String[0]);
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
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
