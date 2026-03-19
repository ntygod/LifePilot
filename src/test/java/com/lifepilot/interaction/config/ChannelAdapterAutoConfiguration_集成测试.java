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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChannelAdapterAutoConfiguration 集成测试。
 *
 * <p>所有通道 Bean 始终注册（无条件），运行时通过 {@link ChannelConfigProvider}
 * 检查 enabled 状态实现热加载。此测试验证 Bean 注册和 Context 加载。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
class ChannelAdapterAutoConfiguration_集成测试 {

    // ── 所有通道 Bean 始终注册 ──────────────────────────────────

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class 所有通道Bean始终注册 {

        private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void configure(DynamicPropertyRegistry registry) {
            var tmpDir = System.getProperty("java.io.tmpdir");
            registry.add("spring.datasource.url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-all-" + DB_ID + ".db").toString().replace("\\", "/"));
            registry.add("lifepilot.memory.vector-db-url",
                    () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-all-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
        }

        @Autowired
        ApplicationContext context;

        @Test
        void Gateway_Bean注册成功() {
            assertThat(context.getBean(MessageGateway.class)).isNotNull();
        }

        @Test
        void 所有通道适配器_始终注册() {
            assertThat(context.getBean(WecomChannelAdapter.class)).isNotNull();
            assertThat(context.getBean(DingtalkChannelAdapter.class)).isNotNull();
            assertThat(context.getBean(FeishuChannelAdapter.class)).isNotNull();
        }

        @Test
        void 所有AuthStrategy_始终注册() {
            assertThat(context.getBean(WecomAuthStrategy.class)).isNotNull();
            assertThat(context.getBean(DingtalkAuthStrategy.class)).isNotNull();
            assertThat(context.getBean(FeishuAuthStrategy.class)).isNotNull();
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
        void ChannelConfigProvider_注册成功() {
            assertThat(context.getBean(ChannelConfigProvider.class)).isNotNull();
        }
    }
}
