package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.channel.LogNotificationChannel;
import com.lifepilot.agent.proactive.channel.NotificationChannel;
import com.lifepilot.agent.proactive.channel.PassiveNotificationQueue;
import com.lifepilot.agent.proactive.config.ProactiveAutoConfiguration;
import com.lifepilot.agent.proactive.model.NotificationType;
import com.lifepilot.agent.proactive.model.ProactiveNotification;
import com.lifepilot.agent.proactive.model.Urgency;
import com.lifepilot.interaction.tray.TrayManager;
import com.lifepilot.interaction.tray.TrayNotificationChannel;
import com.lifepilot.interaction.tray.config.TrayAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * NotificationDispatcher 多通道集成测试 — 验证 ProactiveAutoConfiguration 与 TrayAutoConfiguration
 * 同时激活时，NotificationDispatcher 收集到所有 NotificationChannel Bean 并正确分发。
 *
 * <p>使用 ApplicationContextRunner 轻量加载，Mock TrayManager 避免 AWT 依赖。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
class NotificationDispatcher_多通道集成测试 {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    ProactiveAutoConfiguration.class,
                    TrayAutoConfiguration.class
            ))
            .withUserConfiguration(MockTrayManagerConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:sqlite::memory:",
                    "lifepilot.agent.proactive.enabled=true",
                    "lifepilot.tray.enabled=true"
            );

    // ── 通道注册验证 ────────────────────────────────────────────

    @Test
    void 两个AutoConfiguration同时激活_NotificationDispatcher注入两个通道() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NotificationDispatcher.class);
            assertThat(context).hasSingleBean(LogNotificationChannel.class);
            assertThat(context).hasSingleBean(TrayNotificationChannel.class);

            // 验证 Spring 收集到两个 NotificationChannel Bean
            var channels = context.getBeansOfType(NotificationChannel.class);
            assertThat(channels).hasSize(2);
            assertThat(channels.values())
                    .extracting(NotificationChannel::id)
                    .containsExactlyInAnyOrder("log", "tray");
        });
    }

    @Test
    void HIGH通知_分发到LogChannel和TrayChannel() {
        contextRunner.run(context -> {
            var dispatcher = context.getBean(NotificationDispatcher.class);
            var trayManager = context.getBean(TrayManager.class);
            var notification = buildNotification(Urgency.HIGH);

            dispatcher.dispatch(notification);

            // TrayManager.displayNotification 应被调用（HIGH → WARNING）
            verify(trayManager).displayNotification(
                    eq("LifePilot"),
                    eq(notification.content()),
                    eq(java.awt.TrayIcon.MessageType.WARNING)
            );
        });
    }

    @Test
    void MEDIUM通知_分发到LogChannel和TrayChannel() {
        contextRunner.run(context -> {
            var dispatcher = context.getBean(NotificationDispatcher.class);
            var trayManager = context.getBean(TrayManager.class);
            var notification = buildNotification(Urgency.MEDIUM);

            dispatcher.dispatch(notification);

            // TrayManager.displayNotification 应被调用（MEDIUM → INFO）
            verify(trayManager).displayNotification(
                    eq("LifePilot"),
                    eq(notification.content()),
                    eq(java.awt.TrayIcon.MessageType.INFO)
            );
        });
    }

    @Test
    void LOW通知_不通过任何通道发送_仅入队() {
        contextRunner.run(context -> {
            var dispatcher = context.getBean(NotificationDispatcher.class);
            var trayManager = context.getBean(TrayManager.class);
            var passiveQueue = context.getBean(PassiveNotificationQueue.class);
            var notification = buildNotification(Urgency.LOW);

            dispatcher.dispatch(notification);

            // TrayManager 不应被调用
            verify(trayManager, never()).displayNotification(any(), any(), any());
            // 被动队列应有一条通知
            assertThat(passiveQueue.drainAll()).containsExactly(notification);
        });
    }

    @Test
    void 仅ProactiveAutoConfiguration激活_只有LogChannel() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        ProactiveAutoConfiguration.class,
                        TrayAutoConfiguration.class
                ))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:sqlite::memory:",
                        "lifepilot.agent.proactive.enabled=true",
                        "lifepilot.tray.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationDispatcher.class);
                    assertThat(context).hasSingleBean(LogNotificationChannel.class);
                    assertThat(context).doesNotHaveBean(TrayNotificationChannel.class);

                    var channels = context.getBeansOfType(NotificationChannel.class);
                    assertThat(channels).hasSize(1);
                    assertThat(channels.values().iterator().next().id()).isEqualTo("log");
                });
    }

    // ── Mock 配置 ───────────────────────────────────────────────

    @Configuration
    static class MockTrayManagerConfig {
        @Bean
        TrayManager trayManager() {
            var mock = mock(TrayManager.class);
            when(mock.isNotificationPaused()).thenReturn(false);
            return mock;
        }
    }

    // ── 辅助方法 ────────────────────────────────────────────────

    private static ProactiveNotification buildNotification(Urgency urgency) {
        return new ProactiveNotification(
                UUID.randomUUID().toString(),
                NotificationType.DEADLINE_REMINDER,
                urgency,
                "集成测试通知内容",
                "test",
                "PENDING",
                Instant.now()
        );
    }
}
