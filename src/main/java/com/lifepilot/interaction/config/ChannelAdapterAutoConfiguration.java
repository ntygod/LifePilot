package com.lifepilot.interaction.config;

import java.util.List;

import com.lifepilot.config.threadpool.SharedScheduler;
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
    public WecomCrypto wecomCrypto(ChannelConfigProvider configProvider) {
        var config = configProvider.getWecomConfig();
        return new WecomCrypto(
                config.encodingAesKey() != null && !config.encodingAesKey().isBlank()
                        ? config.encodingAesKey()
                        : "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                config.corpId() != null ? config.corpId() : ""
        );
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.wecom.enabled", havingValue = "true")
    public WecomSignatureVerifier wecomSignatureVerifier(ChannelConfigProvider configProvider) {
        return new WecomSignatureVerifier(configProvider.getWecomConfig().token());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.wecom.enabled", havingValue = "true")
    public WecomApiClient wecomApiClient(ChannelConfigProvider configProvider) {
        return new WecomApiClient(configProvider.getWecomConfig(), RestClient.create());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.wecom.enabled", havingValue = "true")
    public WecomMessageConverter wecomMessageConverter() {
        return new WecomMessageConverter();
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.wecom.enabled", havingValue = "true")
    public WecomChannelAdapter wecomChannelAdapter(@Lazy MessageGateway gateway, GatewayProperties properties,
                                                   WecomCrypto crypto, WecomSignatureVerifier verifier,
                                                   WecomApiClient apiClient, WecomMessageConverter converter,
                                                   SharedScheduler sharedScheduler) {
        log.info("注册 WecomChannelAdapter");
        return new WecomChannelAdapter(gateway, properties, crypto, verifier, apiClient, converter, sharedScheduler);
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.wecom.enabled", havingValue = "true")
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
    public DingtalkApiClient dingtalkApiClient(ChannelConfigProvider configProvider) {
        return new DingtalkApiClient(configProvider.getDingtalkConfig(), RestClient.create());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.dingtalk.enabled", havingValue = "true")
    public DingtalkMessageConverter dingtalkMessageConverter() {
        return new DingtalkMessageConverter();
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.dingtalk.enabled", havingValue = "true")
    public DingtalkChannelAdapter dingtalkChannelAdapter(@Lazy MessageGateway gateway, GatewayProperties properties,
                                                         DingtalkSignatureVerifier verifier,
                                                         DingtalkApiClient apiClient,
                                                         DingtalkMessageConverter converter,
                                                         SharedScheduler sharedScheduler) {
        log.info("注册 DingtalkChannelAdapter");
        return new DingtalkChannelAdapter(gateway, properties, verifier, apiClient, converter, sharedScheduler);
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.dingtalk.enabled", havingValue = "true")
    public DingtalkAuthStrategy dingtalkAuthStrategy(DingtalkSignatureVerifier verifier,
                                                     GatewayProperties properties) {
        return new DingtalkAuthStrategy(verifier, properties);
    }

    // ── 飞书通道 ──────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.feishu.enabled", havingValue = "true")
    public FeishuCrypto feishuCrypto(ChannelConfigProvider configProvider) {
        var config = configProvider.getFeishuConfig();
        return new FeishuCrypto(
                config.encryptKey() != null && !config.encryptKey().isBlank()
                        ? config.encryptKey()
                        : "placeholder-not-configured"
        );
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.feishu.enabled", havingValue = "true")
    public FeishuApiClient feishuApiClient(ChannelConfigProvider configProvider) {
        return new FeishuApiClient(configProvider.getFeishuConfig(), RestClient.create());
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.feishu.enabled", havingValue = "true")
    public FeishuMessageConverter feishuMessageConverter() {
        return new FeishuMessageConverter();
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.feishu.enabled", havingValue = "true")
    public FeishuChannelAdapter feishuChannelAdapter(@Lazy MessageGateway gateway, GatewayProperties properties,
                                                     FeishuCrypto crypto, FeishuApiClient apiClient,
                                                     FeishuMessageConverter converter,
                                                     SharedScheduler sharedScheduler) {
        log.info("注册 FeishuChannelAdapter");
        return new FeishuChannelAdapter(gateway, properties, crypto, apiClient, converter, sharedScheduler);
    }

    @Bean
    @ConditionalOnProperty(name = "lifepilot.gateway.channels.feishu.enabled", havingValue = "true")
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
