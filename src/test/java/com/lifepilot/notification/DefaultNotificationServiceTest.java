package com.lifepilot.notification;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeDeliveryRequest;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultNotificationService} 单元测试。
 *
 * @author zsg
 * @since 2026-03-29
 */
@ExtendWith(MockitoExtension.class)
class DefaultNotificationServiceTest {

    @Mock
    private ChannelInstanceService channelInstanceService;
    @Mock
    private ChannelDeliveryDispatcher channelDeliveryDispatcher;
    @Mock
    private NotificationRepository notificationRepository;

    private NotificationProperties properties;
    private DefaultNotificationService service;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.setEnabled(true);
        service = new DefaultNotificationService(
                channelInstanceService,
                channelDeliveryDispatcher,
                notificationRepository,
                properties
        );
    }

    @Nested
    class 路由逻辑 {

        @Test
        void 默认发送时_仅向可用实例发送() {
            ChannelInstance webInstance = instance("web.default", "webui", "web", true, ChannelInstanceStatus.RUNNING);
            ChannelInstance feishuInstance = instance("feishu.prod", "feishu", "feishu", true, ChannelInstanceStatus.RUNNING);
            ChannelInstance stoppedInstance = instance("wecom.stopped", "wecom", "wecom", true, ChannelInstanceStatus.STOPPED);
            ChannelInstance disabledInstance = instance("dingtalk.disabled", "dingtalk", "dingtalk", false, ChannelInstanceStatus.RUNNING);
            when(channelInstanceService.listAll()).thenReturn(List.of(
                    webInstance,
                    feishuInstance,
                    stoppedInstance,
                    disabledInstance
            ));
            when(channelDeliveryDispatcher.buildContent(any(ResponseContent.class)))
                    .thenReturn(new ChannelRuntimeDeliveryRequest.Content(
                            "text",
                            "测试通知",
                            Map.of("text", "测试通知")
                    ));

            NotificationRequest request = new NotificationRequest(
                    "user-1",
                    new ResponseContent.TextContent("测试通知"),
                    null,
                    "notice.test",
                    Map.of("scene", "default")
            );

            List<String> ids = service.send(request);

            assertEquals(2, ids.size());
            verify(channelDeliveryDispatcher).broadcastNotification(any());
            ArgumentCaptor<ChannelRuntimeDeliveryRequest> deliveryCaptor =
                    ArgumentCaptor.forClass(ChannelRuntimeDeliveryRequest.class);
            verify(channelDeliveryDispatcher).deliver(org.mockito.ArgumentMatchers.eq(feishuInstance), deliveryCaptor.capture());
            ChannelRuntimeDeliveryRequest delivery = deliveryCaptor.getValue();
            assertEquals("feishu.prod", delivery.instanceId());
            assertEquals(DeliveryMode.ASYNC_PUSH, delivery.deliveryMode());
            assertEquals("user-1", delivery.target().userId());
            assertEquals("notice.test", delivery.metadata().get("typeId"));
            assertTrue(delivery.metadata().containsKey("notificationId"));
            verify(notificationRepository, times(2)).save(any(NotificationRecord.class));
        }

        @Test
        void 指定实例选择器时_只发送到命中的实例() {
            ChannelInstance webInstance = instance("web.default", "webui", "web", true, ChannelInstanceStatus.RUNNING);
            ChannelInstance feishuInstance = instance("feishu.prod", "feishu", "feishu", true, ChannelInstanceStatus.RUNNING);
            when(channelInstanceService.listAll()).thenReturn(List.of(webInstance, feishuInstance));
            when(channelDeliveryDispatcher.buildContent(any(ResponseContent.class)))
                    .thenReturn(new ChannelRuntimeDeliveryRequest.Content(
                            "text",
                            "定向通知",
                            Map.of("text", "定向通知")
                    ));

            NotificationRequest request = new NotificationRequest(
                    "user-1",
                    new ResponseContent.TextContent("定向通知"),
                    "feishu.prod",
                    null,
                    Map.of()
            );

            List<String> ids = service.send(request);

            assertEquals(1, ids.size());
            verify(channelDeliveryDispatcher, never()).broadcastNotification(any());
            verify(channelDeliveryDispatcher).deliver(org.mockito.ArgumentMatchers.eq(feishuInstance), any(ChannelRuntimeDeliveryRequest.class));
            verify(notificationRepository).save(any(NotificationRecord.class));
        }
    }

    @Nested
    class 故障处理 {

        @Test
        void 单渠道失败不中断其他渠道() {
            ChannelInstance webInstance = instance("web.default", "webui", "web", true, ChannelInstanceStatus.RUNNING);
            ChannelInstance feishuInstance = instance("feishu.prod", "feishu", "feishu", true, ChannelInstanceStatus.RUNNING);
            when(channelInstanceService.listAll()).thenReturn(List.of(webInstance, feishuInstance));
            when(channelDeliveryDispatcher.buildContent(any(ResponseContent.class)))
                    .thenReturn(new ChannelRuntimeDeliveryRequest.Content(
                            "text",
                            "部分失败",
                            Map.of("text", "部分失败")
                    ));
            doThrow(new RuntimeException("飞书发送失败"))
                    .when(channelDeliveryDispatcher)
                    .deliver(org.mockito.ArgumentMatchers.eq(feishuInstance), any(ChannelRuntimeDeliveryRequest.class));

            NotificationRequest request = new NotificationRequest(
                    "user-1",
                    new ResponseContent.TextContent("部分失败"),
                    null,
                    null,
                    Map.of("source", "test")
            );

            List<String> ids = service.send(request);

            assertEquals(1, ids.size());
            verify(channelDeliveryDispatcher).broadcastNotification(any());
            verify(channelDeliveryDispatcher).deliver(org.mockito.ArgumentMatchers.eq(feishuInstance), any(ChannelRuntimeDeliveryRequest.class));

            ArgumentCaptor<NotificationRecord> recordCaptor = ArgumentCaptor.forClass(NotificationRecord.class);
            verify(notificationRepository, times(2)).save(recordCaptor.capture());
            List<NotificationRecord> records = recordCaptor.getAllValues();
            assertTrue(records.stream().anyMatch(record -> "SENT".equals(record.status()) && "web.default".equals(record.channel())));
            assertTrue(records.stream().anyMatch(record -> "FAILED".equals(record.status()) && "feishu.prod".equals(record.channel())));
        }

        @Test
        void 通知模块禁用时_返回空列表() {
            properties.setEnabled(false);

            NotificationRequest request = new NotificationRequest(
                    "user-1",
                    new ResponseContent.TextContent("不应发送"),
                    null,
                    null,
                    Map.of()
            );

            List<String> ids = service.send(request);

            assertTrue(ids.isEmpty());
            verify(channelInstanceService, never()).listAll();
            verify(channelDeliveryDispatcher, never()).broadcastNotification(any());
            verify(channelDeliveryDispatcher, never()).deliver(any(ChannelInstance.class), any(ChannelRuntimeDeliveryRequest.class));
            verify(notificationRepository, never()).save(any(NotificationRecord.class));
        }
    }

    private ChannelInstance instance(String instanceId,
                                     String pluginId,
                                     String platform,
                                     boolean enabled,
                                     ChannelInstanceStatus status) {
        Instant now = Instant.parse("2026-03-29T00:00:00Z");
        return new ChannelInstance(
                instanceId,
                pluginId,
                platform,
                instanceId,
                enabled,
                status,
                Map.of(),
                Map.of(),
                null,
                now,
                null,
                now,
                now
        );
    }
}
