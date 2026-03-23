package com.lifepilot.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.channel.converter.MessageConverter;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认通知服务实现。
 *
 * <p>根据 {@link Urgency} 路由通知：HIGH / MEDIUM 通过 {@link ChannelAdapter} 实时推送，
 * LOW 入队 {@link PassiveNotificationQueue}。遍历所有已注册的 ChannelAdapter 广播，
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
    private final PassiveNotificationQueue passiveNotificationQueue;
    private final NotificationProperties properties;

    public DefaultNotificationService(List<ChannelAdapter> channelAdapters,
                                      List<MessageConverter> messageConverters,
                                      NotificationRepository notificationRepository,
                                      PassiveNotificationQueue passiveNotificationQueue,
                                      NotificationProperties properties) {
        this.adapterMap = channelAdapters.stream()
                .collect(Collectors.toMap(ChannelAdapter::channelType, a -> a, (a, b) -> a));
        this.converterMap = messageConverters.stream()
                .collect(Collectors.toMap(MessageConverter::channelType, c -> c, (a, b) -> a));
        this.notificationRepository = notificationRepository;
        this.passiveNotificationQueue = passiveNotificationQueue;
        this.properties = properties;
    }

    @Override
    public List<String> send(NotificationRequest request) {
        if (!properties.isEnabled()) {
            log.debug("通知模块未启用，跳过发送: userId={}", request.targetUserId());
            return List.of();
        }

        // 查询用户通知设置
        var setting = request.typeId() != null
                ? notificationRepository.findSettingByUserIdAndTypeId(request.targetUserId(), request.typeId())
                : Optional.<NotificationSettingRecord>empty();

        // 检查是否启用该类型通知
        if (setting.isPresent() && !setting.get().enabled()) {
            log.debug("用户已禁用该类型通知: userId={}, typeId={}", request.targetUserId(), request.typeId());
            return List.of();
        }

        // 检查 urgency 是否低于用户设置的最低阈值
        var minUrgency = setting.map(NotificationSettingRecord::minUrgency).orElse(Urgency.LOW);
        if (request.urgency().ordinal() > minUrgency.ordinal()) {
            log.debug("通知紧急度低于用户阈值: userId={}, urgency={}, minUrgency={}",
                    request.targetUserId(), request.urgency(), minUrgency);
            return List.of();
        }

        var now = Instant.now();
        var contentJson = serializeContent(request.content());
        var metadataJson = serializeMetadata(request.metadata());

        // LOW 紧急度 → 入队被动队列
        if (request.urgency() == Urgency.LOW) {
            return handleLowUrgency(request, contentJson, metadataJson, now);
        }

        // HIGH / MEDIUM → 实时推送
        return handleHighMediumUrgency(request, setting.orElse(null), contentJson, metadataJson, now);
    }

    private List<String> handleLowUrgency(NotificationRequest request, String contentJson,
                                           String metadataJson, Instant now) {
        var id = UUID.randomUUID().toString();
        var queueEntry = new PassiveQueueEntry(
                id, request.targetUserId(), request.typeId(),
                request.urgency(), contentJson, false, now);
        passiveNotificationQueue.enqueue(queueEntry);

        // 持久化通知历史
        var record = new NotificationRecord(
                id, request.targetUserId(), request.typeId(),
                request.urgency(), contentJson, "passive",
                "UNREAD", "SENT", metadataJson, now, now, now);
        persistRecord(record);

        return List.of(id);
    }

    private List<String> handleHighMediumUrgency(NotificationRequest request,
                                                  NotificationSettingRecord setting,
                                                  String contentJson, String metadataJson,
                                                  Instant now) {
        var notificationIds = new ArrayList<String>();

        // 确定目标渠道
        var targetAdapters = resolveTargetAdapters(request, setting);

        for (var adapter : targetAdapters) {
            var channelType = adapter.channelType();
            var id = UUID.randomUUID().toString();
            try {
                // 使用 MessageConverter 转换内容
                var converter = converterMap.get(channelType);
                ResponseContent convertedContent;
                if (converter != null) {
                    var converted = converter.convert(request.content());
                    convertedContent = new ResponseContent.TextContent(converted);
                } else {
                    convertedContent = request.content();
                }

                // 通过 ChannelAdapter 发送
                var response = GatewayResponse.success(channelType, convertedContent);
                adapter.sendResponse(request.targetUserId(), response);

                // 持久化成功记录
                var record = new NotificationRecord(
                        id, request.targetUserId(), request.typeId(),
                        request.urgency(), contentJson, channelType.name(),
                        "UNREAD", "SENT", metadataJson, now, now, now);
                persistRecord(record);
                notificationIds.add(id);

                log.debug("通知发送成功: id={}, channel={}, userId={}",
                        id, channelType, request.targetUserId());
            } catch (Exception e) {
                log.warn("通知发送失败: channel={}, userId={}", channelType, request.targetUserId(), e);
                // 持久化失败记录
                var record = new NotificationRecord(
                        id, request.targetUserId(), request.typeId(),
                        request.urgency(), contentJson, channelType.name(),
                        "UNREAD", "FAILED", metadataJson, now, now, now);
                persistRecord(record);
            }
        }

        return List.copyOf(notificationIds);
    }

    /**
     * 解析目标渠道适配器列表。
     *
     * <p>如果 request 指定了 channel，只发送到该渠道；否则广播所有渠道。
     * 结合用户设置过滤渠道。
     */
    private List<ChannelAdapter> resolveTargetAdapters(NotificationRequest request,
                                                        NotificationSettingRecord setting) {
        if (request.channel() != null) {
            // 指定渠道
            try {
                var channelType = ChannelType.valueOf(request.channel());
                var adapter = adapterMap.get(channelType);
                return adapter != null ? List.of(adapter) : List.of();
            } catch (IllegalArgumentException e) {
                log.warn("未知的渠道类型: channel={}", request.channel());
                return List.of();
            }
        }

        // 广播所有渠道，结合用户设置过滤
        var enabledChannels = setting != null ? setting.channels() : null;
        return adapterMap.values().stream()
                .filter(adapter -> enabledChannels == null || enabledChannels.isEmpty()
                        || enabledChannels.contains(adapter.channelType().name()))
                .toList();
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
