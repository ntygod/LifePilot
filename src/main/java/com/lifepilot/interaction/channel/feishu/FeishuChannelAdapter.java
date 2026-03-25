package com.lifepilot.interaction.channel.feishu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.interaction.channel.AbstractChannelAdapter;
import com.lifepilot.interaction.config.ChannelConfigProvider;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书通道适配器，处理飞书事件订阅 Webhook 回调。
 *
 * <p>处理流程：Challenge 验证 → 加密解密 → event_id 去重 → JSON 解析 → normalize → 异步提交 Gateway。
 * 响应通过 {@link FeishuApiClient} 主动推送。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class FeishuChannelAdapter extends AbstractChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannelAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FeishuCrypto crypto;
    private final FeishuApiClient apiClient;
    private final FeishuMessageConverter converter;
    private final ChannelConfigProvider configProvider;
    private final int eventCacheMaxSize;

    /** 事件去重缓存：eventId → 处理时间戳 */
    private final ConcurrentHashMap<String, Long> eventCache = new ConcurrentHashMap<>();

    public FeishuChannelAdapter(MessageGateway gateway, GatewayProperties properties,
                                FeishuCrypto crypto, FeishuApiClient apiClient,
                                FeishuMessageConverter converter,
                                SharedScheduler sharedScheduler,
                                ChannelConfigProvider configProvider) {
        super(gateway, properties, sharedScheduler);
        this.crypto = crypto;
        this.apiClient = apiClient;
        this.converter = converter;
        this.configProvider = configProvider;
        this.eventCacheMaxSize = properties.channels().feishu().eventCacheMaxSize();
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.FEISHU;
    }

    @Override
    public GatewayMessage normalize(Object rawMessage) {
        @SuppressWarnings("unchecked")
        var event = (Map<String, Object>) rawMessage;

        // 提取 header 和 event 字段（飞书 v2 事件格式）
        var header = getMapField(event, "header");
        var eventBody = getMapField(event, "event");

        if (header == null || eventBody == null) {
            log.warn("飞书事件结构异常: header={}, event={}, 顶层键={}",
                    header != null, eventBody != null, event.keySet());
        }

        String eventId = header != null ? getStringField(header, "event_id", "") : "";
        String eventType = header != null ? getStringField(header, "event_type", "") : "";
        String tenantKey = header != null ? getStringField(header, "tenant_key", "") : "";

        // 提取消息内容
        var message = getMapField(eventBody, "message");
        var sender = getMapField(eventBody, "sender");

        String messageId = message != null ? getStringField(message, "message_id", "") : "";
        String chatId = message != null ? getStringField(message, "chat_id", "") : "";
        String chatType = message != null ? getStringField(message, "chat_type", "p2p") : "p2p";
        String senderId = "";
        if (sender != null) {
            var senderIdMap = getMapField(sender, "sender_id");
            senderId = senderIdMap != null ? getStringField(senderIdMap, "open_id", "") : "";
        }

        log.debug("飞书事件解析: eventId={}, eventType={}, messageId={}, chatId={}, chatType={}, senderId={}",
                eventId, eventType, messageId, chatId, chatType, senderId);

        // 解析消息文本
        String text = extractMessageText(message);
        String cleanText = text.strip();

        MessageContent messageContent = cleanText.startsWith("/")
                ? MessageContent.CommandMessage.parse(cleanText)
                : new MessageContent.TextMessage(cleanText.isBlank() ? "[空消息]" : cleanText);

        var currentConfig = configProvider.getFeishuConfig();
        String currentAppId = currentConfig.appId();
        return GatewayMessage.builder()
                .messageId(messageId.isEmpty() ? null : messageId)
                .channelType(ChannelType.FEISHU)
                .userId(senderId)
                .sessionId("feishu:" + chatId + ":" + senderId)
                .content(messageContent)
                .channelMetadata(new ChannelMetadata.FeishuMetadata(
                        currentAppId != null ? currentAppId : "", tenantKey, messageId,
                        chatId.isEmpty() ? null : chatId, chatType, eventId, eventType))
                .timestamp(Instant.now())
                .attachments(List.of())
                .traceHeaders(Map.of())
                .build();
    }

    /**
     * 处理飞书事件接收请求（POST）。
     *
     * <p>支持三种场景：
     * <ol>
     *   <li>Challenge 验证（首次配置 Webhook URL 时）</li>
     *   <li>加密事件（配置了 Encrypt Key 时）</li>
     *   <li>明文事件</li>
     * </ol>
     *
     * @return 飞书响应 JSON Map
     */
    public Map<String, Object> handleEvent(byte[] rawBody) {
        try {
            Map<String, Object> body;

            // 先尝试作为 UTF-8 JSON 解析
            String jsonBody = new String(rawBody, StandardCharsets.UTF_8);
            try {
                body = MAPPER.readValue(jsonBody, new TypeReference<>() {});
            } catch (Exception parseEx) {
                // JSON 解析失败 — 尝试作为加密数据解密
                var currentCrypto = currentCrypto();
                try {
                    String base64 = Base64.getEncoder().encodeToString(rawBody);
                    String decrypted = currentCrypto.decrypt(base64);
                    body = MAPPER.readValue(decrypted, new TypeReference<>() {});
                } catch (Exception decryptEx) {
                    log.warn("飞书事件处理失败: JSON 解析和解密均失败, bodyLength={}, jsonError={}, decryptError={}",
                            rawBody.length, parseEx.getMessage(), decryptEx.getMessage());
                    return Map.of("code", 0);
                }
            }

            // Challenge 验证
            if (body.containsKey("challenge")) {
                String challenge = body.get("challenge").toString();
                log.info("飞书 Challenge 验证: challenge={}", challenge);
                return Map.of("challenge", challenge);
            }

            // 加密事件解密 — JSON 包裹的 {"encrypt": "..."} 格式
            if (body.containsKey("encrypt")) {
                String encrypted = body.get("encrypt").toString();
                var currentCrypto = currentCrypto();
                try {
                    String decrypted = currentCrypto.decrypt(encrypted);
                    body = MAPPER.readValue(decrypted, new TypeReference<>() {});
                } catch (Exception decryptEx) {
                    log.warn("飞书加密事件解密失败: encryptedLength={}, error={}", encrypted.length(), decryptEx.getMessage());
                    return Map.of("code", 0);
                }

                // 解密后可能是 Challenge
                if (body.containsKey("challenge")) {
                    String challenge = body.get("challenge").toString();
                    log.info("飞书加密 Challenge 验证: challenge={}", challenge);
                    return Map.of("challenge", challenge);
                }
            }

            // 提取 event_id 进行去重
            var header = getMapField(body, "header");
            String eventId = header != null ? getStringField(header, "event_id", "") : "";

            if (!eventId.isEmpty() && isDuplicate(eventId)) {
                log.debug("飞书事件重复，跳过处理: eventId={}", eventId);
                return Map.of("code", 0);
            }

            // normalize 并异步提交
            var message = normalize(body);
            submitAsync(message);
        } catch (Exception e) {
            log.error("飞书事件处理异常", e);
        }
        return Map.of("code", 0);
    }

    @Override
    protected void doStart() {
        log.info("飞书通道适配器启动");
    }

    @Override
    protected void doStop() {
        eventCache.clear();
        log.info("飞书通道适配器停止");
    }

    @Override
    protected void doSendResponse(String userId, GatewayResponse response) {
        // 从 metadata 中提取飞书特定的发送目标信息
        String chatId = response.metadata().containsKey("feishuChatId")
                ? response.metadata().get("feishuChatId").toString() : null;
        String openId = response.metadata().containsKey("feishuOpenId")
                ? response.metadata().get("feishuOpenId").toString() : null;

        // 优先使用 chat_id（群聊），其次 open_id（私聊），最后 fallback 到 userId
        String receiveId;
        String receiveIdType;
        if (chatId != null && !chatId.isBlank()) {
            receiveId = chatId;
            receiveIdType = "chat_id";
        } else if (openId != null && !openId.isBlank()) {
            receiveId = openId;
            receiveIdType = "open_id";
        } else {
            receiveId = userId;
            receiveIdType = "open_id";
        }

        log.debug("飞书消息发送: receiveId={}, receiveIdType={}, chatId={}, openId={}, userId={}",
                receiveId, receiveIdType, chatId, openId, userId);

        ResponseContent content = response.content();
        String text = converter.convert(content);

        if (converter.shouldUseInteractiveCard(content)) {
            apiClient.sendInteractiveCard(receiveId, receiveIdType, text);
        } else if (converter.shouldUseImage(content)) {
            apiClient.sendImage(receiveId, receiveIdType, text);
        } else if (converter.shouldUsePost(content)) {
            apiClient.sendPost(receiveId, receiveIdType, text);
        } else {
            apiClient.sendText(receiveId, receiveIdType, text);
        }
    }

    /**
     * 重写异步提交，将飞书 ChannelMetadata 中的 chatId 和 open_id 注入到 response metadata。
     *
     * <p>基类的 {@code doSendResponse} 只接收 userId 和 response，无法访问原始 message 的 channelMetadata。
     * 通过在 response metadata 中注入飞书特定字段，让 {@code doSendResponse} 能正确选择 receive_id_type。
     */
    @Override
    protected void submitAsync(GatewayMessage message) {
        Thread.ofVirtual()
                .name("channel-async-" + channelType().value())
                .start(() -> {
                    try {
                        var response = gateway.process(message);
                        // 将飞书 chatId 和 open_id 注入到 response metadata
                        var enrichedResponse = enrichResponseWithFeishuMetadata(message, response);
                        sendResponse(message.userId(), enrichedResponse);
                    } catch (Exception e) {
                        log.error("异步处理消息失败: channel={}, userId={}",
                                channelType(), message.userId(), e);
                    }
                });
    }

    private GatewayResponse enrichResponseWithFeishuMetadata(GatewayMessage message, GatewayResponse response) {
        if (!(message.channelMetadata() instanceof ChannelMetadata.FeishuMetadata feishuMeta)) {
            return response;
        }
        var enrichedMetadata = new java.util.LinkedHashMap<>(response.metadata());
        var chatId = feishuMeta.chatId();
        if (chatId != null && !chatId.isBlank()) {
            enrichedMetadata.put("feishuChatId", chatId);
        }
        // open_id 存储在 message.userId() 中（normalize 时从 sender.sender_id.open_id 提取）
        if (message.userId() != null && !message.userId().isBlank()) {
            enrichedMetadata.put("feishuOpenId", message.userId());
        }
        return response.toBuilder().metadata(enrichedMetadata).build();
    }

    // ── 运行时配置 ──────────────────────────────────────────

    /**
     * 获取使用当前 encryptKey 的 FeishuCrypto 实例（支持热加载）。
     *
     * <p>每次调用从 {@link ChannelConfigProvider} 读取最新 encryptKey，
     * 如果与启动时注入的 crypto 一致则复用，否则创建新实例。
     */
    private FeishuCrypto currentCrypto() {
        var config = configProvider.getFeishuConfig();
        String encryptKey = config.encryptKey();
        if (encryptKey != null && !encryptKey.isBlank()) {
            // 检查是否为 mask 值（前端返回的 ****... 被误存入数据库）
            if (encryptKey.startsWith("****")) {
                return crypto;
            }
            return new FeishuCrypto(encryptKey);
        }
        // fallback 到启动时注入的 crypto
        return crypto;
    }

    // ── 事件去重 ──────────────────────────────────────────────

    /**
     * 检查事件是否重复，同时记录新事件。
     *
     * @param eventId 事件 ID
     * @return 如果是重复事件返回 true
     */
    boolean isDuplicate(String eventId) {
        Long existing = eventCache.putIfAbsent(eventId, System.currentTimeMillis());
        if (existing != null) {
            return true;
        }
        // 容量控制：超过上限时清理最早的条目
        if (eventCache.size() > eventCacheMaxSize) {
            evictOldest();
        }
        return false;
    }

    private void evictOldest() {
        // 找到最早的条目并移除
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (var entry : eventCache.entrySet()) {
            if (entry.getValue() < oldestTime) {
                oldestTime = entry.getValue();
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            eventCache.remove(oldestKey);
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────

    /**
     * 从飞书消息 JSON 中提取文本内容。
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private static Boolean parseConfirmationFlag(@Nullable Object rawValue) {
        if (rawValue instanceof Boolean boolValue) {
            return boolValue;
        }
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue.toString().trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "confirm", "confirmed", "approve", "approved", "yes", "ok", "submit" -> true;
            case "false", "cancel", "reject", "rejected", "deny", "denied", "no" -> false;
            default -> null;
        };
    }

    private static String extractMessageText(@Nullable Map<String, Object> message) {
        if (message == null) return "";
        var content = message.get("content");
        if (content instanceof String contentStr) {
            // 飞书消息 content 是 JSON 字符串，如 {"text":"hello"}
            try {
                Map<String, Object> contentMap = MAPPER.readValue(contentStr, new TypeReference<>() {});
                var text = contentMap.get("text");
                return text != null ? text.toString() : "";
            } catch (Exception e) {
                return contentStr;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<String, Object> getMapField(@Nullable Map<String, Object> map, String key) {
        if (map == null) return null;
        var value = map.get(key);
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String getStringField(@Nullable Map<String, Object> map, String key, String defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        var value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
