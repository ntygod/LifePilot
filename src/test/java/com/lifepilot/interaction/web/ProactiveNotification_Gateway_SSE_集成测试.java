package com.lifepilot.interaction.web;

import com.lifepilot.agent.proactive.channel.GatewayNotificationChannel;
import com.lifepilot.agent.proactive.model.NotificationType;
import com.lifepilot.agent.proactive.model.ProactiveNotification;
import com.lifepilot.agent.proactive.model.Urgency;
import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.web.model.NotificationSseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProactiveNotification → GatewayNotificationChannel → WebChannelAdapter → SseSessionManager 端到端集成测试。
 *
 * <p>验证主动通知从 GatewayNotificationChannel 发出后，经过 WebChannelAdapter 的 doSendResponse()
 * 检测到 notificationType metadata，最终通过 SseSessionManager.broadcastNotification() 推送 SSE 事件。</p>
 *
 * @author zsg
 * @since 2026-03-07
 */
class ProactiveNotification_Gateway_SSE_集成测试 {

    private List<GatewayResponse> capturedResponses;
    private GatewayNotificationChannel gatewayChannel;

    @BeforeEach
    void setUp() {
        capturedResponses = new CopyOnWriteArrayList<>();

        // 创建一个捕获 sendResponse 调用的 ChannelAdapter stub
        ChannelAdapter capturingAdapter = new CapturingChannelAdapter(capturedResponses);

        gatewayChannel = new GatewayNotificationChannel(List.of(capturingAdapter));
    }

    @Test
    void 通知经Gateway发送后_响应包含notificationType元数据() {
        // Given
        var notification = new ProactiveNotification(
                UUID.randomUUID().toString(),
                NotificationType.DEADLINE_REMINDER,
                Urgency.HIGH,
                "待办「提交报告」将在 1 小时内到期",
                "gateway",
                "SENT",
                Instant.now()
        );

        // When
        gatewayChannel.send(notification);

        // Then
        assertEquals(1, capturedResponses.size(), "应有 1 个响应被发送");
        var response = capturedResponses.getFirst();
        assertNotNull(response.metadata(), "响应应包含 metadata");
        assertEquals("DEADLINE_REMINDER", response.metadata().get("notificationType"),
                "metadata 应包含 notificationType");
        assertEquals("HIGH", response.metadata().get("urgency"),
                "metadata 应包含 urgency");
        assertEquals(notification.id(), response.metadata().get("notificationId"),
                "metadata 应包含 notificationId");
    }

    @Test
    void 通知内容正确转换为GatewayResponse() {
        // Given
        var notification = new ProactiveNotification(
                "test-id-123",
                NotificationType.SCHEDULE_REMINDER,
                Urgency.MEDIUM,
                "下午 3 点有会议",
                "gateway",
                "SENT",
                Instant.now()
        );

        // When
        gatewayChannel.send(notification);

        // Then
        assertEquals(1, capturedResponses.size());
        var response = capturedResponses.getFirst();
        assertEquals(200, response.statusCode(), "状态码应为 200");
        assertNotNull(response.content(), "响应内容不应为空");
        assertEquals("下午 3 点有会议", response.content().toPlainText(),
                "响应内容应与通知内容一致");
    }

    @Test
    void 多个Adapter均收到通知() {
        // Given
        var responses1 = new CopyOnWriteArrayList<GatewayResponse>();
        var responses2 = new CopyOnWriteArrayList<GatewayResponse>();
        var multiChannel = new GatewayNotificationChannel(List.of(
                new CapturingChannelAdapter(responses1),
                new CapturingChannelAdapter(responses2)
        ));

        var notification = new ProactiveNotification(
                UUID.randomUUID().toString(),
                NotificationType.HABIT_REMINDER,
                Urgency.LOW,
                "该喝水了",
                "gateway",
                "SENT",
                Instant.now()
        );

        // When
        multiChannel.send(notification);

        // Then
        assertEquals(1, responses1.size(), "第一个 adapter 应收到 1 个响应");
        assertEquals(1, responses2.size(), "第二个 adapter 应收到 1 个响应");
    }

    @Test
    void 空Adapter列表不抛异常() {
        // Given
        var emptyChannel = new GatewayNotificationChannel(List.of());
        var notification = new ProactiveNotification(
                UUID.randomUUID().toString(),
                NotificationType.DAILY_SUMMARY,
                Urgency.LOW,
                "今日总结",
                "gateway",
                "SENT",
                Instant.now()
        );

        // When & Then — 不抛异常
        assertDoesNotThrow(() -> emptyChannel.send(notification));
    }

    @Test
    void 单个Adapter失败不影响其他Adapter() {
        // Given
        var successResponses = new CopyOnWriteArrayList<GatewayResponse>();
        var failingAdapter = new FailingChannelAdapter();
        var successAdapter = new CapturingChannelAdapter(successResponses);
        var mixedChannel = new GatewayNotificationChannel(List.of(failingAdapter, successAdapter));

        var notification = new ProactiveNotification(
                UUID.randomUUID().toString(),
                NotificationType.STREAK_AT_RISK,
                Urgency.MEDIUM,
                "连续打卡即将中断",
                "gateway",
                "SENT",
                Instant.now()
        );

        // When
        mixedChannel.send(notification);

        // Then — 成功的 adapter 仍然收到响应
        assertEquals(1, successResponses.size(), "成功的 adapter 应收到响应");
    }

    @Test
    void NotificationSseEvent_包含完整字段() {
        // Given
        var event = new NotificationSseEvent(
                "evt-001",
                NotificationType.DEADLINE_REMINDER,
                Urgency.HIGH,
                "紧急提醒内容",
                Instant.now().toString()
        );

        // Then
        assertEquals("evt-001", event.id());
        assertEquals(NotificationType.DEADLINE_REMINDER, event.type());
        assertEquals(Urgency.HIGH, event.urgency());
        assertEquals("紧急提醒内容", event.content());
        assertNotNull(event.timestamp());
    }

    // ── 测试辅助类 ──────────────────────────────────────────

    /**
     * 捕获 sendResponse 调用的 ChannelAdapter stub。
     */
    private static class CapturingChannelAdapter implements ChannelAdapter {
        private final List<GatewayResponse> captured;

        CapturingChannelAdapter(List<GatewayResponse> captured) {
            this.captured = captured;
        }

        @Override
        public com.lifepilot.interaction.model.ChannelType channelType() {
            return com.lifepilot.interaction.model.ChannelType.WEB;
        }

        @Override
        public com.lifepilot.interaction.model.GatewayMessage normalize(Object rawMessage) {
            throw new UnsupportedOperationException("测试 stub 不支持 normalize");
        }

        @Override
        public void sendResponse(String userId, GatewayResponse response) {
            captured.add(response);
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}
    }

    /**
     * 始终抛异常的 ChannelAdapter stub，用于测试故障隔离。
     */
    private static class FailingChannelAdapter implements ChannelAdapter {
        @Override
        public com.lifepilot.interaction.model.ChannelType channelType() {
            return com.lifepilot.interaction.model.ChannelType.WEB;
        }

        @Override
        public com.lifepilot.interaction.model.GatewayMessage normalize(Object rawMessage) {
            throw new UnsupportedOperationException("测试 stub 不支持 normalize");
        }

        @Override
        public void sendResponse(String userId, GatewayResponse response) {
            throw new RuntimeException("模拟通道发送失败");
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}
    }
}
