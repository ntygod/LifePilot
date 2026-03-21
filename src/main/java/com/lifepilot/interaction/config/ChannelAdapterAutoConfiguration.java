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
import com.lifepilot.interaction.web.service.WebUserConfirmationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * 通道适配器自动配置 — 始终注册所有通道 Bean，运行时通过 {@link ChannelConfigProvider} 检查启用状态。
 *
 * <p>所有通道的 Crypto / ApiClient / Adapter / AuthStrategy Bean 无条件注册，
 * 凭证未配置时使用占位符值。Webhook 入口在 {@code WebhookController} 中通过
 * {@link ChannelConfigProvider} 实时检查 enabled 状态，实现热加载（无需重启）。</p>
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
    public WecomSignatureVerifier wecomSignatureVerifier(ChannelConfigProvider configProvider) {
        return new WecomSignatureVerifier(configProvider.getWecomConfig().token());
    }

    @Bean
    public WecomApiClient wecomApiClient(ChannelConfigProvider configProvider) {
        return new WecomApiClient(configProvider, RestClient.create());
    }

    @Bean
    public WecomMessageConverter wecomMessageConverter() {
        return new WecomMessageConverter();
    }

    @Bean
    public WecomChannelAdapter wecomChannelAdapter(@Lazy MessageGateway gateway, GatewayProperties properties,
                                                   WecomCrypto crypto, WecomSignatureVerifier verifier,
                                                   WecomApiClient apiClient, WecomMessageConverter converter,
                                                   SharedScheduler sharedScheduler) {
        log.info("注册 WecomChannelAdapter");
        return new WecomChannelAdapter(gateway, properties, crypto, verifier, apiClient, converter, sharedScheduler);
    }

    @Bean
    public WecomAuthStrategy wecomAuthStrategy(WecomSignatureVerifier verifier, GatewayProperties properties,
                                               ChannelConfigProvider configProvider) {
        return new WecomAuthStrategy(verifier, properties, configProvider);
    }

    // ── 钉钉通道 ──────────────────────────────────────────────

    @Bean
    public DingtalkSignatureVerifier dingtalkSignatureVerifier() {
        return new DingtalkSignatureVerifier();
    }

    @Bean
    public DingtalkApiClient dingtalkApiClient(ChannelConfigProvider configProvider) {
        return new DingtalkApiClient(configProvider, RestClient.create());
    }

    @Bean
    public DingtalkMessageConverter dingtalkMessageConverter() {
        return new DingtalkMessageConverter();
    }

    @Bean
    public DingtalkChannelAdapter dingtalkChannelAdapter(@Lazy MessageGateway gateway, GatewayProperties properties,
                                                         DingtalkSignatureVerifier verifier,
                                                         DingtalkApiClient apiClient,
                                                         DingtalkMessageConverter converter,
                                                         SharedScheduler sharedScheduler) {
        log.info("注册 DingtalkChannelAdapter");
        return new DingtalkChannelAdapter(gateway, properties, verifier, apiClient, converter, sharedScheduler);
    }

    @Bean
    public DingtalkAuthStrategy dingtalkAuthStrategy(DingtalkSignatureVerifier verifier,
                                                     GatewayProperties properties,
                                                     ChannelConfigProvider configProvider) {
        return new DingtalkAuthStrategy(verifier, properties, configProvider);
    }

    // ── 飞书通道 ──────────────────────────────────────────────

    @Bean
    public FeishuCrypto feishuCrypto(ChannelConfigProvider configProvider) {
        var config = configProvider.getFeishuConfig();
        return new FeishuCrypto(
                config.encryptKey() != null && !config.encryptKey().isBlank()
                        ? config.encryptKey()
                        : "placeholder-not-configured"
        );
    }

    @Bean
    public FeishuApiClient feishuApiClient(ChannelConfigProvider configProvider) {
        return new FeishuApiClient(configProvider, RestClient.create());
    }

    @Bean
    public FeishuMessageConverter feishuMessageConverter() {
        return new FeishuMessageConverter();
    }

    @Bean
    public FeishuChannelAdapter feishuChannelAdapter(@Lazy MessageGateway gateway, GatewayProperties properties,
                                                     FeishuCrypto crypto, FeishuApiClient apiClient,
                                                     FeishuMessageConverter converter,
                                                     SharedScheduler sharedScheduler,
                                                     ChannelConfigProvider configProvider,
                                                     @Autowired(required = false) @Lazy WebUserConfirmationService confirmationService) {
        log.info("注册 FeishuChannelAdapter");
        return new FeishuChannelAdapter(gateway, properties, crypto, apiClient, converter, sharedScheduler, configProvider, confirmationService);
    }

    @Bean
    public FeishuAuthStrategy feishuAuthStrategy(ChannelConfigProvider configProvider) {
        return new FeishuAuthStrategy(configProvider);
    }

    // ── 重试调度器 ──────────────────────────────────────────

    @Bean
    public FailedMessageRetryScheduler failedMessageRetryScheduler(
            List<AbstractChannelAdapter> adapters, JdbcTemplate jdbcTemplate,
            GatewayProperties properties) {
        log.info("注册 FailedMessageRetryScheduler");
        return new FailedMessageRetryScheduler(adapters, jdbcTemplate, properties);
    }
}
