package com.lifepilot.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeDeliveryRequest;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 默认通知服务实现。
 *
 * <p>通知统一按渠道实例路由。Web 本地渠道继续输出原有通知 payload，
 * 外部渠道实例通过统一 connector delivery 协议主动发送。</p>
 *
 * @author zsg
 * @since 2026-03-13
 */
public class DefaultNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultNotificationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChannelInstanceService channelInstanceService;
    private final ChannelDeliveryDispatcher channelDeliveryDispatcher;
    private final NotificationRepository notificationRepository;
    private final NotificationProperties properties;

    public DefaultNotificationService(ChannelInstanceService channelInstanceService,
                                      ChannelDeliveryDispatcher channelDeliveryDispatcher,
                                      NotificationRepository notificationRepository,
                                      NotificationProperties properties) {
        this.channelInstanceService = channelInstanceService;
        this.channelDeliveryDispatcher = channelDeliveryDispatcher;
        this.notificationRepository = notificationRepository;
        this.properties = properties;
    }

    @Override
    public List<String> send(NotificationRequest request) {
        if (!properties.isEnabled()) {
            log.debug("通知模块未启用，跳过发送: userId={}", request.targetUserId());
            return List.of();
        }

        var targetInstances = resolveTargetInstances(request);
        if (targetInstances.isEmpty()) {
            log.warn("无可用通知渠道实例: userId={}, selector={}", request.targetUserId(), request.channel());
            return List.of();
        }

        var now = Instant.now();
        var contentJson = serializeContent(request.content());
        var metadataJson = serializeMetadata(request.metadata());
        var notificationIds = new ArrayList<String>();

        for (var instance : targetInstances) {
            var id = UUID.randomUUID().toString();
            var sentRecord = new NotificationRecord(
                    id,
                    request.targetUserId(),
                    request.typeId(),
                    contentJson,
                    instance.instanceId(),
                    "UNREAD",
                    "SENT",
                    metadataJson,
                    now,
                    now,
                    now
            );
            try {
                sendToInstance(instance, request, sentRecord);
                persistRecord(sentRecord);
                notificationIds.add(id);
                log.debug("通知发送成功: id={}, instanceId={}, userId={}",
                        id, instance.instanceId(), request.targetUserId());
            } catch (Exception e) {
                log.warn("通知发送失败: instanceId={}, userId={}",
                        instance.instanceId(), request.targetUserId(), e);
                persistRecord(new NotificationRecord(
                        id,
                        request.targetUserId(),
                        request.typeId(),
                        contentJson,
                        instance.instanceId(),
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

    private void sendToInstance(ChannelInstance instance,
                                NotificationRequest request,
                                NotificationRecord record) {
        if ("web".equalsIgnoreCase(instance.platform())) {
            channelDeliveryDispatcher.broadcastNotification(toNotificationPayload(record));
            return;
        }

        var delivery = new ChannelRuntimeDeliveryRequest(
                instance.instanceId(),
                record.id(),
                DeliveryMode.ASYNC_PUSH,
                new ChannelRuntimeDeliveryRequest.Target(request.targetUserId(), null, Map.of()),
                channelDeliveryDispatcher.buildContent(request.content()),
                List.of(),
                buildDeliveryMetadata(request, record)
        );
        channelDeliveryDispatcher.deliver(instance, delivery);
    }

    private Map<String, Object> buildDeliveryMetadata(NotificationRequest request, NotificationRecord record) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("notificationId", record.id());
        metadata.put("channel", record.channel());
        if (request.typeId() != null && !request.typeId().isBlank()) {
            metadata.put("typeId", request.typeId());
        }
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            metadata.put("notificationMetadata", request.metadata());
        }
        return Map.copyOf(metadata);
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

    private List<ChannelInstance> resolveTargetInstances(NotificationRequest request) {
        return channelInstanceService.listAll().stream()
                .filter(this::isAvailable)
                .filter(instance -> matchesSelector(instance, request.channel()))
                .toList();
    }

    private boolean isAvailable(ChannelInstance instance) {
        return instance.enabled() && instance.status() == ChannelInstanceStatus.RUNNING;
    }

    private boolean matchesSelector(ChannelInstance instance, String selector) {
        if (selector == null || selector.isBlank()) {
            return true;
        }
        String normalizedSelector = selector.trim();
        return normalizedSelector.equalsIgnoreCase(instance.instanceId())
                || normalizedSelector.equalsIgnoreCase(instance.platform())
                || normalizedSelector.equalsIgnoreCase(instance.pluginId())
                || normalizedSelector.equalsIgnoreCase(instance.platform().toUpperCase());
    }

    private void persistRecord(NotificationRecord record) {
        try {
            notificationRepository.save(record);
        } catch (Exception e) {
            log.warn("通知记录持久化失败: id={}", record.id(), e);
        }
    }

    private String serializeContent(com.lifepilot.interaction.model.ResponseContent content) {
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
