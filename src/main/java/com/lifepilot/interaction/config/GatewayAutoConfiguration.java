package com.lifepilot.interaction.config;

import java.util.List;

import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.gateway.DefaultMessageGateway;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewarePipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;

/**
 * Gateway 核心框架自动配置。
 *
 * <p>注册 {@link MiddlewarePipeline} 和 {@link MessageGateway} Bean，
 * 自动收集所有 {@link GatewayMiddleware} 和 {@link ChannelAdapter} Bean。
 * 在 {@link ApplicationReadyEvent} 触发时启动网关。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.gateway.enabled", matchIfMissing = true)
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayAutoConfiguration.class);

    /**
     * 注册中间件管道，自动收集所有 {@link GatewayMiddleware} Bean 并按 order 排序。
     *
     * <p>如果没有注册任何中间件 Bean，将创建空管道。</p>
     *
     * @param middlewares Spring 容器中所有 GatewayMiddleware Bean，无匹配时为空列表
     * @return 中间件管道实例
     */
    @Bean
    public MiddlewarePipeline middlewarePipeline(List<GatewayMiddleware> middlewares) {
        log.info("注册 MiddlewarePipeline，收集到 {} 个中间件", middlewares.size());
        return new MiddlewarePipeline(middlewares);
    }

    /**
     * 注册消息网关，自动注册所有 {@link ChannelAdapter} Bean。
     *
     * <p>如果没有注册任何通道适配器 Bean，将创建无通道的网关。</p>
     *
     * @param pipeline 中间件管道
     * @param adapters Spring 容器中所有 ChannelAdapter Bean，无匹配时为空列表
     * @return 消息网关实例
     */
    @Bean
    public MessageGateway messageGateway(MiddlewarePipeline pipeline, List<ChannelAdapter> adapters) {
        var gateway = new DefaultMessageGateway(pipeline);
        for (var adapter : adapters) {
            gateway.registerChannel(adapter);
            log.info("注册通道适配器: type={}", adapter.channelType());
        }
        log.info("注册 MessageGateway，已注册 {} 个通道适配器", adapters.size());
        return gateway;
    }

    /**
     * 应用就绪后启动消息网关。
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        if (ctx.containsBean("messageGateway")) {
            var gateway = ctx.getBean(MessageGateway.class);
            gateway.start();
            log.info("ApplicationReady: 消息网关已启动");
        }
    }
}
