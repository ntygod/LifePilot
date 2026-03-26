package com.lifepilot.notification;

import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.channel.converter.MessageConverter;
import com.lifepilot.interaction.config.ChannelConfigProvider;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultNotificationService} 单元测试。
 *
 * @author zsg
 * @since 2026-03-13
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private ChannelAdapter webAdapter;
    @Mock private ChannelAdapter feishuAdapter;
    @Mock private MessageConverter webConverter;
    @Mock private MessageConverter feishuConverter;
    @Mock private ChannelConfigProvider channelConfigProvider;
    @Mock private SseSessionManager sseSessionManager;

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
                properties,
                channelConfigProvider,
                sseSessionManager
        );
    }

    @Nested
    class 路由逻辑 {

        @Test
        void 默认发送时_向WEB广播并跳过未启用渠道() {
            var request = new NotificationRequest(
                    "user-1",
                    new ResponseContent.TextContent("测试通知"),
                    null,
                    null,
                    Map.of()
            );

            var ids = service.send(request);

            assertEquals(1, ids.size());
            verify(sseSessionManager).broadcastNotification(any());
            verify(webAdapter, never()).sendResponse(any(), any());
            verify(feishuAdapter, never()).sendResponse(any(), any());
        }

        @Test
        void 指定渠道时只发送到该渠道() {
            when(channelConfigProvider.getFeishuConfig())
                    .thenReturn(new com.lifepilot.interaction.config.GatewayProperties.ChannelsProperties.FeishuChannelProperties(
                            true, "app", "secret", null, null, 10_000
                    ));
            var request = new NotificationRequest(
                    "user-1",
                    new ResponseContent.TextContent("定向测试"),
                    "FEISHU",
                    null,
                    Map.of()
            );

            var ids = service.send(request);

            assertEquals(1, ids.size());
            verify(feishuAdapter, times(1)).sendResponse(eq("user-1"), any(GatewayResponse.class));
            verify(sseSessionManager, never()).broadcastNotification(any());
        }
    }

    @Nested
    class 故障处理 {

        @Test
        void 单渠道失败不中断其他渠道() {
            when(channelConfigProvider.getFeishuConfig())
                    .thenReturn(new com.lifepilot.interaction.config.GatewayProperties.ChannelsProperties.FeishuChannelProperties(
                            true, "app", "secret", null, null, 10_000
                    ));
            doThrow(new RuntimeException("飞书发送失败"))
                    .when(feishuAdapter).sendResponse(any(), any());

            var request = new NotificationRequest(
                    "user-1",
                    new ResponseContent.TextContent("部分失败"),
                    null,
                    null,
                    Map.of()
            );

            var ids = service.send(request);

            assertEquals(1, ids.size());
            verify(sseSessionManager).broadcastNotification(any());
            verify(feishuAdapter).sendResponse(eq("user-1"), any());
        }

        @Test
        void 通知模块禁用时_返回空列表() {
            properties.setEnabled(false);

            var request = new NotificationRequest(
                    "user-1",
                    new ResponseContent.TextContent("不应发送"),
                    null,
                    null,
                    Map.of()
            );

            var ids = service.send(request);

            assertTrue(ids.isEmpty());
            verify(sseSessionManager, never()).broadcastNotification(any());
            verify(feishuAdapter, never()).sendResponse(any(), any());
        }
    }
}
