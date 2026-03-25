package com.lifepilot.notification;

import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.channel.converter.MessageConverter;
import com.lifepilot.interaction.config.ChannelConfigProvider;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultNotificationService} 单元测试。
 *
 * @author zsg
 * @since 2026-03-13
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private PassiveNotificationQueue passiveNotificationQueue;
    @Mock private ChannelAdapter webAdapter;
    @Mock private ChannelAdapter feishuAdapter;
    @Mock private MessageConverter webConverter;
    @Mock private MessageConverter feishuConverter;
    @Mock private ChannelConfigProvider channelConfigProvider;

    private NotificationProperties properties;
    private DefaultNotificationService service;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.setEnabled(true);

        lenient().when(webAdapter.channelType()).thenReturn(ChannelType.WEB);
        lenient().when(feishuAdapter.channelType()).thenReturn(ChannelType.FEISHU);
        lenient().when(webConverter.channelType()).thenReturn(ChannelType.WEB);
        lenient().when(feishuConverter.channelType()).thenReturn(ChannelType.FEISHU);
        lenient().when(webConverter.convert(any())).thenReturn("converted-web");
        lenient().when(feishuConverter.convert(any())).thenReturn("converted-feishu");
        lenient().when(channelConfigProvider.getFeishuConfig())
                .thenReturn(new com.lifepilot.interaction.config.GatewayProperties.ChannelsProperties.FeishuChannelProperties(
                        false, null, null, null, null, 10_000
                ));
        lenient().when(channelConfigProvider.getDingtalkConfig())
                .thenReturn(new com.lifepilot.interaction.config.GatewayProperties.ChannelsProperties.DingtalkChannelProperties(
                        false, null, null, null
                ));
        lenient().when(channelConfigProvider.getWecomConfig())
                .thenReturn(new com.lifepilot.interaction.config.GatewayProperties.ChannelsProperties.WecomChannelProperties(
                        false, null, null, null, null, null
                ));

        service = new DefaultNotificationService(
                List.of(webAdapter, feishuAdapter),
                List.of(webConverter, feishuConverter),
                notificationRepository,
                passiveNotificationQueue,
                properties,
                channelConfigProvider
        );
    }

    // ── urgency 路由 ──────────────────────────────────────

    @Nested
    class Urgency路由 {

        @Test
        void urgency为HIGH时_通过已启用渠道发送() {
            var request = new NotificationRequest("user-1",
                    new ResponseContent.TextContent("紧急通知"), Urgency.HIGH,
                    null, null, Map.of());

            var ids = service.send(request);

            assertFalse(ids.isEmpty());
            verify(webAdapter).sendResponse(eq("user-1"), any(GatewayResponse.class));
            verify(feishuAdapter, never()).sendResponse(eq("user-1"), any(GatewayResponse.class));
            verify(passiveNotificationQueue, never()).enqueue(any());
        }

        @Test
        void urgency为MEDIUM时_通过已启用渠道发送() {
            var request = new NotificationRequest("user-1",
                    new ResponseContent.TextContent("中等通知"), Urgency.MEDIUM,
                    null, null, Map.of());

            var ids = service.send(request);

            assertFalse(ids.isEmpty());
            verify(webAdapter).sendResponse(eq("user-1"), any(GatewayResponse.class));
            verify(feishuAdapter, never()).sendResponse(eq("user-1"), any(GatewayResponse.class));
            verify(passiveNotificationQueue, never()).enqueue(any());
        }

        @Test
        void urgency为LOW时_入队PassiveNotificationQueue() {
            var request = new NotificationRequest("user-1",
                    new ResponseContent.TextContent("低优先级通知"), Urgency.LOW,
                    null, null, Map.of());

            var ids = service.send(request);

            assertEquals(1, ids.size());
            verify(passiveNotificationQueue).enqueue(any(PassiveQueueEntry.class));
            verify(webAdapter, never()).sendResponse(any(), any());
            verify(feishuAdapter, never()).sendResponse(any(), any());
        }
    }

    // ── 广播逻辑 ──────────────────────────────────────────

    @Nested
    class 路由逻辑 {

        @Test
        void 指定渠道时只发送到该渠道() {
            var request = new NotificationRequest("user-1",
                    new ResponseContent.TextContent("定向测试"), Urgency.HIGH,
                    "WEB", null, Map.of());

            service.send(request);

            verify(webAdapter, times(1)).sendResponse(eq("user-1"), any());
            verify(feishuAdapter, never()).sendResponse(eq("user-1"), any());
        }
    }

    // ── 用户设置过滤 ──────────────────────────────────────

    @Nested
    class 用户设置过滤 {

        @Test
        void 用户禁用该类型通知_跳过发送() {
            var setting = new NotificationSettingRecord(
                    "s-1", "user-1", "alert", false,
                    List.of("WEB"), Urgency.LOW, Instant.now(), Instant.now());
            when(notificationRepository.findSettingByUserIdAndTypeId("user-1", "alert"))
                    .thenReturn(Optional.of(setting));

            var request = new NotificationRequest("user-1",
                    new ResponseContent.TextContent("被禁用"), Urgency.HIGH,
                    null, "alert", Map.of());

            var ids = service.send(request);

            assertTrue(ids.isEmpty());
            verify(webAdapter, never()).sendResponse(any(), any());
        }

        @Test
        void urgency低于用户阈值_跳过发送() {
            // 用户设置 minUrgency=HIGH，发送 MEDIUM 应被跳过
            var setting = new NotificationSettingRecord(
                    "s-1", "user-1", "alert", true,
                    List.of("WEB"), Urgency.HIGH, Instant.now(), Instant.now());
            when(notificationRepository.findSettingByUserIdAndTypeId("user-1", "alert"))
                    .thenReturn(Optional.of(setting));

            var request = new NotificationRequest("user-1",
                    new ResponseContent.TextContent("低于阈值"), Urgency.MEDIUM,
                    null, "alert", Map.of());

            var ids = service.send(request);

            assertTrue(ids.isEmpty());
        }
    }

    // ── 单渠道失败不中断 ──────────────────────────────────

    @Nested
    class 单渠道失败不中断 {

        @Test
        void 一个渠道失败_其他渠道继续发送() {
            doThrow(new RuntimeException("飞书发送失败"))
                    .when(feishuAdapter).sendResponse(any(), any());
            when(channelConfigProvider.getFeishuConfig())
                    .thenReturn(new com.lifepilot.interaction.config.GatewayProperties.ChannelsProperties.FeishuChannelProperties(
                            true, "app", "secret", null, null, 10_000
                    ));

            var request = new NotificationRequest("user-1",
                    new ResponseContent.TextContent("部分失败"), Urgency.HIGH,
                    null, null, Map.of());

            var ids = service.send(request);

            // web 渠道应成功
            assertEquals(1, ids.size());
            verify(webAdapter).sendResponse(eq("user-1"), any());
            verify(feishuAdapter).sendResponse(eq("user-1"), any());
        }
    }

    // ── 通知禁用 ──────────────────────────────────────────

    @Nested
    class 通知禁用 {

        @Test
        void properties_enabled为false_返回空列表() {
            properties.setEnabled(false);

            var request = new NotificationRequest("user-1",
                    new ResponseContent.TextContent("不应发送"), Urgency.HIGH,
                    null, null, Map.of());

            var ids = service.send(request);

            assertTrue(ids.isEmpty());
            verify(webAdapter, never()).sendResponse(any(), any());
            verify(feishuAdapter, never()).sendResponse(any(), any());
        }
    }
}
