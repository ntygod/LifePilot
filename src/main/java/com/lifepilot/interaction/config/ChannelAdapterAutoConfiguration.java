package com.lifepilot.interaction.config;

import java.util.List;

import com.lifepilot.interaction.channel.AbstractChannelAdapter;
import com.lifepilot.interaction.channel.FailedMessageRetryScheduler;
import com.lifepilot.interaction.channel.dingtalk.DingtalkApiClient;
import com.lifepilot.interaction.channel.dingtalk.DingtalkChannelAdapter;
import com.lifepilot.interaction.channel.dingtalk.DingtalkMessageConverter;
import com.lifepilot.interaction.channel.dingtalk.DingtalkSignatureVerifier;
import com.lifepilot.interaction.channel.feishu.FeishuApiClient;
import com.lifepilot.interaction.channel.feishu.FeishuChannelAdapter;
import com.lifepilot.interaction.channel.feishu.FeishuCrypto;
import com.lifepilot.interaction.channel.feishu.FeishuMessageConverter;
import com.lifepilot.interaction.channel.wecom.WecomApiClient;
import com.lifepilot.interaction.channel.wecom.WecomChannelAdapter;
import com.lifepilot.interaction.channel.wecom.WecomCrypto;
import com.lifepilot.interaction.channel.wecom.WecomMessageConverter;
import com.lifepilot.interaction.channel.wecom.WecomSignatureVerifier;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.middleware.auth.DingtalkAuthStrategy;
import com.lifepilot.interaction.middleware.auth.FeishuAuthStrategy;
import com.lifepilot.interaction.middleware.auth.WecomAuthStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * 通道适配器自动配置，根据配置条件注册企微/钉钉/飞书通道 Bean。
 *
 * <p>在 {@link GatewayMiddlewareAutoConfiguration} 之后加载，确保中间件和 Gateway 已注册。
 * 每个通道的启用由 {@code lifepilot.gateway.channels.xxx.enabled} 控制。
 *
 * @author zsg
 * @since 2026-02-26
 */
@AutoConfiguration(after = GatewayMiddlewareAutoConfiguration.class)
@ConditionalOnProperty(name = "lifepilot.gateway.enabled", matchIfMissing = true)
@EnableScheduling
public class ChannelAdapterAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChannelAdapterAutoConfiguration.class);

    // ── 企业微信通道 ──────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.wecom.enabled", havingValue = "true")
    public WecomCrypto wecomCrypto(GatewayProperties properties) {
        var config = properties.channels().wecom();
        if (config.encodingAesKey() == null || config.encodingAesKey().isBlank()) {
            log.error("企微通道已启用但 encodingAesKey 未配置，跳过注册");
            return null;
        }
        return new WecomCrypto(config.encodingAesKey(), config.corpId());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.wecom.enabled", havingValue = "true")
    public WecomSignatureVerifier wecomSignatureVerifier(GatewayProperties properties) {
        return new WecomSignatureVerifier(properties.channels().wecom().token());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.wecom.enabled", havingValue = "true")
    public WecomApiClient wecomApiClient(GatewayProperties properties) {
        return new WecomApiClient(properties.channels().wecom(), RestClient.create());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.wecom.enabled", havingValue = "true")
    public WecomMessageConverter wecomMessageConverter() {
        return new WecomMessageConverter();
    }

    @Bean
    @ConditionalOnBean(WecomCrypto.class)
    public WecomChannelAdapter wecomChannelAdapter(@Lazy MessageGateway gateway, GatewayProperties properties,
                                                   WecomCrypto crypto, WecomSignatureVerifier verifier,
                                                   WecomApiClient apiClient, WecomMessageConverter converter) {
        log.info("注册 WecomChannelAdapter");
        return new WecomChannelAdapter(gateway, properties, crypto, verifier, apiClient, converter);
    }

    @Bean
    @ConditionalOnBean(WecomChannelAdapter.class)
    public WecomAuthStrategy wecomAuthStrategy(WecomSignatureVerifier verifier, GatewayProperties properties) {
        return new WecomAuthStrategy(verifier, properties);
    }

    // ── 钉钉通道 ──────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.dingtalk.enabled", havingValue = "true")
    public DingtalkSignatureVerifier dingtalkSignatureVerifier() {
        return new DingtalkSignatureVerifier();
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.dingtalk.enabled", havingValue = "true")
    public DingtalkApiClient dingtalkApiClient(GatewayProperties properties) {
        return new DingtalkApiClient(properties.channels().dingtalk(), RestClient.create());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.dingtalk.enabled", havingValue = "true")
    public DingtalkMessageConverter dingtalkMessageConverter() {
        return new DingtalkMessageConverter();
    }

    @Bean
    @ConditionalOnBean(DingtalkSignatureVerifier.class)
    public DingtalkChannelAdapter dingtalkChannelAdapter(@Lazy MessageGateway gateway, GatewayProperties properties,
                                                         DingtalkSignatureVerifier verifier,
                                                         DingtalkApiClient apiClient,
                                                         DingtalkMessageConverter converter) {
        log.info("注册 DingtalkChannelAdapter");
        return new DingtalkChannelAdapter(gateway, properties, verifier, apiClient, converter);
    }

    @Bean
    @ConditionalOnBean(DingtalkChannelAdapter.class)
    public DingtalkAuthStrategy dingtalkAuthStrategy(DingtalkSignatureVerifier verifier,
                                                     GatewayProperties properties) {
        return new DingtalkAuthStrategy(verifier, properties);
    }

    // ── 飞书通道 ──────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.feishu.enabled", havingValue = "true")
    public FeishuCrypto feishuCrypto(GatewayProperties properties) {
        var config = properties.channels().feishu();
        if (config.encryptKey() == null || config.encryptKey().isBlank()) {
            log.error("飞书通道已启用但 encryptKey 未配置，跳过注册");
            return null;
        }
        return new FeishuCrypto(config.encryptKey());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.feishu.enabled", havingValue = "true")
    public FeishuApiClient feishuApiClient(GatewayProperties properties) {
        return new FeishuApiClient(properties.channels().feishu(), RestClient.create());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.feishu.enabled", havingValue = "true")
    public FeishuMessageConverter feishuMessageConverter() {
        return new FeishuMessageConverter();
    }

    @Bean
    @ConditionalOnBean(FeishuCrypto.class)
    public FeishuChannelAdapter feishuChannelAdapter(@Lazy MessageGateway gateway, GatewayProperties properties,
                                                     FeishuCrypto crypto, FeishuApiClient apiClient,
                                                     FeishuMessageConverter converter) {
        log.info("注册 FeishuChannelAdapter");
        return new FeishuChannelAdapter(gateway, properties, crypto, apiClient, converter);
    }

    @Bean
    @ConditionalOnBean(FeishuChannelAdapter.class)
    public FeishuAuthStrategy feishuAuthStrategy(GatewayProperties properties) {
        return new FeishuAuthStrategy(properties);
    }

    // ── WebhookController 由 @RestController 组件扫描注册，重试调度器 ──

    @Bean
    @ConditionalOnBean(AbstractChannelAdapter.class)
    public FailedMessageRetryScheduler failedMessageRetryScheduler(
            List<AbstractChannelAdapter> adapters, JdbcTemplate jdbcTemplate,
            GatewayProperties properties) {
        log.info("注册 FailedMessageRetryScheduler");
        return new FailedMessageRetryScheduler(adapters, jdbcTemplate, properties);
    }
}
