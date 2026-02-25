package com.lifepilot.interaction.channel.feishu;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.channel.AbstractChannelAdapter;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.model.ResponseContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

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
    private final String appId;
    private final String verificationToken;
    private final int eventCacheMaxSize;

    /** 事件去重缓存：eventId → 处理时间戳 */
    private final ConcurrentHashMap<String, Long> eventCache = new ConcurrentHashMap<>();

    public FeishuChannelAdapter(MessageGateway gateway, GatewayProperties properties,
                                FeishuCrypto crypto, FeishuApiClient apiClient,
                                FeishuMessageConverter converter) {
        super(gateway, properties);
        this.crypto = crypto;
        this.apiClient = apiClient;
        this.converter = converter;
        var feishuConfig = properties.channels().feishu();
        this.appId = feishuConfig.appId();
        this.verificationToken = feishuConfig.verificationToken();
        this.eventCacheMaxSize = feishuConfig.eventCacheMaxSize();
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

        // 解析消息文本
        String text = extractMessageText(message);
        String cleanText = text.strip();

        MessageContent messageContent = cleanText.startsWith("/")
                ? MessageContent.CommandMessage.parse(cleanText)
                : new MessageContent.TextMessage(cleanText.isBlank() ? "[空消息]" : cleanText);

        return GatewayMessage.builder()
                .messageId(messageId.isEmpty() ? null : messageId)
                .channelType(ChannelType.FEISHU)
                .userId(senderId)
                .sessionId("feishu:" + chatId + ":" + senderId)
                .content(messageContent)
                .channelMetadata(new ChannelMetadata.FeishuMetadata(
                        appId != null ? appId : "", tenantKey, messageId,
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
     * @param jsonBody JSON 事件体
     * @return 飞书响应 JSON Map
     */
    public Map<String, Object> handleEvent(String jsonBody) {
        try {
            Map<String, Object> body = MAPPER.readValue(jsonBody, new TypeReference<>() {});

            // Challenge 验证
            if (body.containsKey("challenge")) {
                String challenge = body.get("challenge").toString();
                log.info("飞书 Challenge 验证: challenge={}", challenge);
                return Map.of("challenge", challenge);
            }

            // 加密事件解密
            if (body.containsKey("encrypt")) {
                String encrypted = body.get("encrypt").toString();
                String decrypted = crypto.decrypt(encrypted);
                body = MAPPER.readValue(decrypted, new TypeReference<>() {});

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
        // 飞书通过 chat_id 发送消息，从 metadata 中提取
        String chatId = extractChatId(response);
        ResponseContent content = response.content();
        String text = converter.convert(content);

        if (converter.shouldUsePost(content)) {
            apiClient.sendPost(chatId != null ? chatId : userId, text);
        } else {
            apiClient.sendText(chatId != null ? chatId : userId, text);
        }
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

    @Nullable
    private static String extractChatId(GatewayResponse response) {
        if (response.metadata().containsKey("chatId")) {
            return response.metadata().get("chatId").toString();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<String, Object> getMapField(@Nullable Map<String, Object> map, String key) {
        if (map == null) return null;
        var value = map.get(key);
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String getStringField(Map<String, Object> map, String key, String defaultValue) {
        var value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
