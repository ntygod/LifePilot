package com.lifepilot.agent.proactive.channel;

import com.lifepilot.agent.proactive.model.ProactiveNotification;
import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gateway 通知通道 — 桥接 NotificationChannel 与 ChannelAdapter。
 *
 * <p>将 {@link ProactiveNotification} 转换为 {@link GatewayResponse}，
 * 遍历所有已注册的 {@link ChannelAdapter} 进行广播。
 * 单通道失败不中断其他通道的发送。</p>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class GatewayNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(GatewayNotificationChannel.class);

    private final List<ChannelAdapter> adapters;

    public GatewayNotificationChannel(List<ChannelAdapter> adapters) {
        this.adapters = adapters;
    }

    @Override
    public String id() {
        return "gateway";
    }

    @Override
    public void send(ProactiveNotification notification) {
        if (adapters.isEmpty()) {
            log.info("无可用 ChannelAdapter，通知降级为日志: typeId={}", notification.typeId());
            return;
        }

        for (var adapter : adapters) {
            try {
                var response = toGatewayResponse(notification, adapter);
                adapter.sendResponse("system", response);
            } catch (Exception e) {
                log.warn("通知发送失败: channel={}, type={}, error={}",
                        adapter.channelType(), notification.typeId(), e.getMessage());
            }
        }
    }

    /**
     * 将 ProactiveNotification 转换为 GatewayResponse。
     */
    private GatewayResponse toGatewayResponse(ProactiveNotification notification, ChannelAdapter adapter) {
        return new GatewayResponse(
                notification.id(),
                adapter.channelType(),
                new ResponseContent.TextContent(notification.content()),
                List.of(),
                Map.of(
                        "notificationType", notification.typeId(),
                        "urgency", notification.urgency().name(),
                        "notificationId", notification.id()
                ),
                Duration.ZERO,
                null,
                200,
                null
        );
    }
}
