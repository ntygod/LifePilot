package com.lifepilot.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.channel.converter.MessageConverter;
import com.lifepilot.interaction.config.ChannelConfigProvider;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 默认通知服务实现。
 *
 * <p>统一采用直接发送策略。若请求显式指定渠道则定向发送，
 * 否则仅在已启用渠道内路由。
 * 单渠道失败记录 WARN 日志，不中断其他渠道。
 *
 * @author zsg
 * @since 2026-03-13
 */
public class DefaultNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultNotificationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<ChannelType, ChannelAdapter> adapterMap;
    private final Map<ChannelType, MessageConverter> converterMap;
    private final NotificationRepository notificationRepository;
    private final NotificationProperties properties;
    @Nullable
    private final ChannelConfigProvider channelConfigProvider;
    @Nullable
    private final SseSessionManager sseSessionManager;

    public DefaultNotificationService(List<ChannelAdapter> channelAdapters,
                                      List<MessageConverter> messageConverters,
                                      NotificationRepository notificationRepository,
                                      NotificationProperties properties) {
        this(channelAdapters, messageConverters, notificationRepository, properties, null, null);
    }

    public DefaultNotificationService(List<ChannelAdapter> channelAdapters,
                                      List<MessageConverter> messageConverters,
                                      NotificationRepository notificationRepository,
                                      NotificationProperties properties,
                                      @Nullable ChannelConfigProvider channelConfigProvider,
                                      @Nullable SseSessionManager sseSessionManager) {
        this.adapterMap = channelAdapters.stream()
                .collect(Collectors.toMap(ChannelAdapter::channelType, a -> a, (a, b) -> a));
        this.converterMap = messageConverters.stream()
                .collect(Collectors.toMap(MessageConverter::channelType, c -> c, (a, b) -> a));
        this.notificationRepository = notificationRepository;
        this.properties = properties;
        this.channelConfigProvider = channelConfigProvider;
        this.sseSessionManager = sseSessionManager;
    }

    @Override
    public List<String> send(NotificationRequest request) {
        if (!properties.isEnabled()) {
            log.debug("通知模块未启用，跳过发送: userId={}", request.targetUserId());
            return List.of();
        }

        var targetAdapters = resolveTargetAdapters(request);
        if (targetAdapters.isEmpty()) {
            log.warn("无可用通知渠道: userId={}, channel={}", request.targetUserId(), request.channel());
            return List.of();
        }

        var now = Instant.now();
        var contentJson = serializeContent(request.content());
        var metadataJson = serializeMetadata(request.metadata());
        var notificationIds = new ArrayList<String>();

        for (var adapter : targetAdapters) {
            var channelType = adapter.channelType();
            var id = UUID.randomUUID().toString();
            var sentRecord = new NotificationRecord(
                    id,
                    request.targetUserId(),
                    request.typeId(),
                    contentJson,
                    channelType.name(),
                    "UNREAD",
                    "SENT",
                    metadataJson,
                    now,
                    now,
                    now
            );
            try {
                sendToChannel(adapter, request, sentRecord);
                persistRecord(sentRecord);
                notificationIds.add(id);
                log.debug("通知发送成功: id={}, channel={}, userId={}",
                        id, channelType, request.targetUserId());
            } catch (Exception e) {
                log.warn("通知发送失败: channel={}, userId={}", channelType, request.targetUserId(), e);
                persistRecord(new NotificationRecord(
                        id,
                        request.targetUserId(),
                        request.typeId(),
                        contentJson,
                        channelType.name(),
                        "UNREAD",
                        "FAILED",
                        metadataJson,
                        now,
                        now,
                        now
                ));
            }
        }

        return List.copyOf(notificationIds);
    }

    private void sendToChannel(ChannelAdapter adapter,
                               NotificationRequest request,
                               NotificationRecord record) {
        var channelType = adapter.channelType();
        if (channelType == ChannelType.WEB) {
            broadcastWebNotification(record);
            return;
        }

        var converter = converterMap.get(channelType);
        ResponseContent convertedContent;
        if (converter != null) {
            var converted = converter.convert(request.content());
            convertedContent = new ResponseContent.TextContent(converted);
        } else {
            convertedContent = request.content();
        }

        adapter.sendResponse(request.targetUserId(), GatewayResponse.success(channelType, convertedContent));
    }

    private void broadcastWebNotification(NotificationRecord record) {
        if (sseSessionManager == null) {
            throw new IllegalStateException("SseSessionManager 不可用，无法发送 WEB 通知");
        }
        sseSessionManager.broadcastNotification(toNotificationPayload(record));
    }

    private Map<String, Object> toNotificationPayload(NotificationRecord record) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", record.id());
        payload.put("userId", record.userId());
        if (record.typeId() != null && !record.typeId().isBlank()) {
            payload.put("typeId", record.typeId());
        }
        payload.put("contentJson", record.contentJson());
        payload.put("channel", record.channel());
        payload.put("readStatus", record.readStatus());
        payload.put("status", record.status());
        if (record.metadataJson() != null && !record.metadataJson().isBlank()) {
            payload.put("metadataJson", record.metadataJson());
        }
        payload.put("sentAt", record.sentAt().toString());
        return Map.copyOf(payload);
    }

    /**
     * 解析目标渠道适配器列表。
     *
     * <p>如果 request 指定了 channel，只发送到该渠道；否则在已启用渠道内路由。
     */
    private List<ChannelAdapter> resolveTargetAdapters(NotificationRequest request) {
        if (request.channel() != null) {
            try {
                var channelType = ChannelType.valueOf(request.channel());
                var adapter = adapterMap.get(channelType);
                return adapter != null && isChannelEnabled(channelType) ? List.of(adapter) : List.of();
            } catch (IllegalArgumentException e) {
                log.warn("未知的渠道类型: channel={}", request.channel());
                return List.of();
            }
        }

        return adapterMap.values().stream()
                .filter(adapter -> isChannelEnabled(adapter.channelType()))
                .toList();
    }

    private boolean isChannelEnabled(ChannelType channelType) {
        return switch (channelType) {
            case WEB -> true;
            case FEISHU -> channelConfigProvider == null || channelConfigProvider.getFeishuConfig().enabled();
            case DINGTALK -> channelConfigProvider == null || channelConfigProvider.getDingtalkConfig().enabled();
            case WECOM -> channelConfigProvider == null || channelConfigProvider.getWecomConfig().enabled();
        };
    }

    private void persistRecord(NotificationRecord record) {
        try {
            notificationRepository.save(record);
        } catch (Exception e) {
            log.warn("通知记录持久化失败: id={}", record.id(), e);
        }
    }

    private String serializeContent(ResponseContent content) {
        try {
            return MAPPER.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            log.warn("通知内容序列化失败", e);
            return content.toPlainText();
        }
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("通知元数据序列化失败", e);
            return null;
        }
    }
}
