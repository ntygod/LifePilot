package com.lifepilot.interaction.web.config;

import com.lifepilot.agent.proactive.channel.PassiveNotificationQueue;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.web.adapter.WebChannelAdapter;
import com.lifepilot.interaction.web.controller.WebExceptionHandler;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 模块自动配置。
 *
 * <p>在 {@code lifepilot.gateway.channels.web.enabled=true} 时注册所有 Web 相关 Bean：
 * {@link WebChannelAdapter}、{@link SseSessionManager}、{@link WebExceptionHandler} 和 CORS 配置。</p>
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
    public WebChannelAdapter webChannelAdapter(MessageGateway gateway,
                                                GatewayProperties gatewayProperties,
                                                AttachmentRepository attachmentRepository,
                                                SseSessionManager sseSessionManager,
                                                @Autowired(required = false) PassiveNotificationQueue passiveNotificationQueue) {
        log.info("注册 WebChannelAdapter");
        return new WebChannelAdapter(gateway, gatewayProperties, attachmentRepository,
                sseSessionManager, passiveNotificationQueue);
    }

    @Bean
    public SseSessionManager sseSessionManager(WebProperties properties) {
        log.info("注册 SseSessionManager");
        return new SseSessionManager(properties);
    }

    @Bean
    public WebExceptionHandler webExceptionHandler() {
        log.info("注册 WebExceptionHandler");
        return new WebExceptionHandler();
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
