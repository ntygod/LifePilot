package com.lifepilot.interaction.config;

import java.nio.file.Path;
import java.util.UUID;

import com.lifepilot.interaction.channel.dingtalk.DingtalkChannelAdapter;
import com.lifepilot.interaction.channel.feishu.FeishuChannelAdapter;
import com.lifepilot.interaction.channel.wecom.WecomChannelAdapter;
import com.lifepilot.interaction.channel.webhook.WebhookController;
import com.lifepilot.interaction.channel.FailedMessageRetryScheduler;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.middleware.auth.DingtalkAuthStrategy;
import com.lifepilot.interaction.middleware.auth.FeishuAuthStrategy;
import com.lifepilot.interaction.middleware.auth.WecomAuthStrategy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChannelAdapterAutoConfiguration 集成测试，验证配置驱动的通道条件注册。
 *
 * @author zsg
 * @since 2026-02-26
 */
class ChannelAdapterAutoConfiguration_集成测试 {

    // ── 所有通道禁用时 ──────────────────────────────────────────

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class 所有通道禁用 {

        private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) {
            var tmpDir = System.getProperty("java.io.tmpdir");
            registry.add("spring.datasource.url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-all-off-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.memory.vector-db-url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-all-off-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.gateway.channels.wecom.enabled", () -> "false");
            registry.add("lifepilot.gateway.channels.dingtalk.enabled", () -> "false");
            registry.add("lifepilot.gateway.channels.feishu.enabled", () -> "false");
        }

        @Autowired
        ApplicationContext context;

        @Test
        void Gateway_Bean注册成功() {
            assertThat(context.getBean(MessageGateway.class)).isNotNull();
        }

        @Test
        void 通道适配器Bean_均未注册() {
            assertThatThrownBy(() -> context.getBean(WecomChannelAdapter.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> context.getBean(DingtalkChannelAdapter.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> context.getBean(FeishuChannelAdapter.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }

        @Test
        void WebhookController_始终注册_因组件扫描() {
            // WebhookController 通过 @RestController 组件扫描注册，
            // 内部对 null 适配器做优雅降级，无需条件注册
            assertThat(context.getBean(WebhookController.class)).isNotNull();
        }

        @Test
        void AuthStrategy_通道策略未注册() {
            assertThatThrownBy(() -> context.getBean(WecomAuthStrategy.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> context.getBean(DingtalkAuthStrategy.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> context.getBean(FeishuAuthStrategy.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }

        @Test
        void FailedMessageRetryScheduler_未注册() {
            assertThatThrownBy(() -> context.getBean(FailedMessageRetryScheduler.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    // ── 企微通道启用时 ──────────────────────────────────────────

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class 企微通道启用 {

        private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) {
            var tmpDir = System.getProperty("java.io.tmpdir");
            registry.add("spring.datasource.url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-wecom-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.memory.vector-db-url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-wecom-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.gateway.channels.wecom.enabled", () -> "true");
            registry.add("lifepilot.gateway.channels.wecom.corp-id", () -> "test-corp");
            registry.add("lifepilot.gateway.channels.wecom.agent-id", () -> "1000001");
            registry.add("lifepilot.gateway.channels.wecom.secret", () -> "test-secret");
            registry.add("lifepilot.gateway.channels.wecom.token", () -> "test-token");
            // 43 字符的合法 Base64 编码 AES Key
            registry.add("lifepilot.gateway.channels.wecom.encoding-aes-key",
                    () -> "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
            registry.add("lifepilot.gateway.channels.dingtalk.enabled", () -> "false");
            registry.add("lifepilot.gateway.channels.feishu.enabled", () -> "false");
        }

        @Autowired
        ApplicationContext context;

        @Test
        void WecomChannelAdapter_注册成功() {
            assertThat(context.getBean(WecomChannelAdapter.class)).isNotNull();
        }

        @Test
        void WecomAuthStrategy_注册成功() {
            assertThat(context.getBean(WecomAuthStrategy.class)).isNotNull();
        }

        @Test
        void WebhookController_注册成功() {
            assertThat(context.getBean(WebhookController.class)).isNotNull();
        }

        @Test
        void FailedMessageRetryScheduler_注册成功() {
            assertThat(context.getBean(FailedMessageRetryScheduler.class)).isNotNull();
        }

        @Test
        void 其他通道适配器_未注册() {
            assertThatThrownBy(() -> context.getBean(DingtalkChannelAdapter.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> context.getBean(FeishuChannelAdapter.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    // ── 钉钉通道启用时 ──────────────────────────────────────────

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class 钉钉通道启用 {

        private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) {
            var tmpDir = System.getProperty("java.io.tmpdir");
            registry.add("spring.datasource.url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-dingtalk-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.memory.vector-db-url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-dingtalk-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.gateway.channels.wecom.enabled", () -> "false");
            registry.add("lifepilot.gateway.channels.dingtalk.enabled", () -> "true");
            registry.add("lifepilot.gateway.channels.dingtalk.app-key", () -> "test-app-key");
            registry.add("lifepilot.gateway.channels.dingtalk.app-secret", () -> "test-app-secret");
            registry.add("lifepilot.gateway.channels.dingtalk.robot-code", () -> "test-robot");
            registry.add("lifepilot.gateway.channels.feishu.enabled", () -> "false");
        }

        @Autowired
        ApplicationContext context;

        @Test
        void DingtalkChannelAdapter_注册成功() {
            assertThat(context.getBean(DingtalkChannelAdapter.class)).isNotNull();
        }

        @Test
        void DingtalkAuthStrategy_注册成功() {
            assertThat(context.getBean(DingtalkAuthStrategy.class)).isNotNull();
        }

        @Test
        void WebhookController_注册成功() {
            assertThat(context.getBean(WebhookController.class)).isNotNull();
        }
    }

    // ── 飞书通道启用时 ──────────────────────────────────────────

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class 飞书通道启用 {

        private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) {
            var tmpDir = System.getProperty("java.io.tmpdir");
            registry.add("spring.datasource.url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-feishu-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.memory.vector-db-url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-feishu-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.gateway.channels.wecom.enabled", () -> "false");
            registry.add("lifepilot.gateway.channels.dingtalk.enabled", () -> "false");
            registry.add("lifepilot.gateway.channels.feishu.enabled", () -> "true");
            registry.add("lifepilot.gateway.channels.feishu.app-id", () -> "test-app-id");
            registry.add("lifepilot.gateway.channels.feishu.app-secret", () -> "test-app-secret");
            registry.add("lifepilot.gateway.channels.feishu.verification-token", () -> "test-verify-token");
            registry.add("lifepilot.gateway.channels.feishu.encrypt-key", () -> "test-encrypt-key-value");
        }

        @Autowired
        ApplicationContext context;

        @Test
        void FeishuChannelAdapter_注册成功() {
            assertThat(context.getBean(FeishuChannelAdapter.class)).isNotNull();
        }

        @Test
        void FeishuAuthStrategy_注册成功() {
            assertThat(context.getBean(FeishuAuthStrategy.class)).isNotNull();
        }

        @Test
        void WebhookController_注册成功() {
            assertThat(context.getBean(WebhookController.class)).isNotNull();
        }
    }
}
