package com.lifepilot.interaction.channel.dingtalk;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

/**
 * 钉钉通道适配器，处理钉钉机器人 Webhook 回调消息。
 *
 * <p>处理流程：签名验证 → JSON 解析 → @ 前缀去除 → normalize → 同步提交 Gateway。
 * 同步超时时降级为异步推送（通过 {@link DingtalkApiClient}）。
 * 响应超过 500 字符或含 Markdown 时使用 ActionCard 类型。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class DingtalkChannelAdapter extends AbstractChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(DingtalkChannelAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DingtalkSignatureVerifier signatureVerifier;
    private final DingtalkApiClient apiClient;
    private final DingtalkMessageConverter converter;
    private final String appSecret;
    private final String robotCode;
    private final long timestampToleranceMs;

    public DingtalkChannelAdapter(MessageGateway gateway, GatewayProperties properties,
                                  DingtalkSignatureVerifier signatureVerifier,
                                  DingtalkApiClient apiClient, DingtalkMessageConverter converter) {
        super(gateway, properties);
        this.signatureVerifier = signatureVerifier;
        this.apiClient = apiClient;
        this.converter = converter;
        this.appSecret = properties.channels().dingtalk().appSecret();
        this.robotCode = properties.channels().dingtalk().robotCode();
        this.timestampToleranceMs = properties.webhook().timestampToleranceSeconds() * 1000L;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.DINGTALK;
    }

    @Override
    public GatewayMessage normalize(Object rawMessage) {
        @SuppressWarnings("unchecked")
        var fields = (Map<String, Object>) rawMessage;

        String senderId = getStringField(fields, "senderStaffId",
                getStringField(fields, "senderId", "unknown"));
        String conversationId = getStringField(fields, "conversationId", "");
        String conversationType = getStringField(fields, "conversationType", "1");
        String senderNick = getStringField(fields, "senderNick", "");
        String msgId = getStringField(fields, "msgId", "");
        boolean isAtAll = Boolean.TRUE.equals(fields.get("isAtAll"));

        // 提取文本内容并去除 @ 前缀
        String text = extractText(fields);
        String cleanText = removeAtPrefix(text);

        // 文本以 / 开头转为 CommandMessage，否则转为 TextMessage
        MessageContent messageContent = cleanText.startsWith("/")
                ? MessageContent.CommandMessage.parse(cleanText)
                : new MessageContent.TextMessage(cleanText.isBlank() ? "[空消息]" : cleanText);

        return GatewayMessage.builder()
                .messageId(msgId.isEmpty() ? null : msgId)
                .channelType(ChannelType.DINGTALK)
                .userId(senderId)
                .sessionId("dingtalk:" + conversationId)
                .content(messageContent)
                .channelMetadata(new ChannelMetadata.DingtalkMetadata(
                        robotCode != null ? robotCode : "",
                        conversationId, conversationType, senderNick,
                        "", 0L, isAtAll))
                .timestamp(Instant.now())
                .attachments(List.of())
                .traceHeaders(Map.of())
                .build();
    }

    /**
     * 处理钉钉消息接收请求（POST）。
     *
     * <p>同步优先：在 20 秒 Webhook 超时内尝试同步返回响应。
     * 超时时降级为异步推送。
     *
     * @param headers 请求头（sign, timestamp）
     * @param jsonBody JSON 消息体
     * @return 钉钉响应 JSON Map
     */
    public Map<String, Object> handleMessage(Map<String, String> headers, String jsonBody) {
        try {
            // 签名验证
            String sign = headers.get("sign");
            String timestampStr = headers.get("timestamp");
            if (sign == null || timestampStr == null) {
                log.warn("钉钉消息缺少签名参数");
                return Map.of();
            }

            long timestamp = Long.parseLong(timestampStr);
            long now = System.currentTimeMillis();
            if (Math.abs(now - timestamp) > timestampToleranceMs) {
                log.warn("钉钉消息时间戳过期: timestamp={}", timestamp);
                return Map.of();
            }

            if (!signatureVerifier.verify(sign, timestamp, appSecret)) {
                log.warn("钉钉消息签名验证失败");
                return Map.of("errcode", 401, "errmsg", "签名验证失败");
            }

            // 解析 JSON
            Map<String, Object> body = MAPPER.readValue(jsonBody, new TypeReference<>() {});
            var message = normalize(body);

            // 同步提交到 Gateway
            GatewayResponse response = submitSync(message);

            // 转换响应格式
            ResponseContent content = response.content();
            String text = converter.convert(content);

            if (converter.shouldUseActionCard(content)) {
                String title = content instanceof ResponseContent.CardContent card
                        ? card.title() : "LifePilot";
                return Map.of(
                        "msgtype", "actionCard",
                        "actionCard", Map.of("title", title, "text", text)
                );
            }
            return Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", text)
            );
        } catch (Exception e) {
            log.error("钉钉消息处理异常", e);
            return Map.of();
        }
    }

    @Override
    protected void doStart() {
        log.info("钉钉通道适配器启动");
    }

    @Override
    protected void doStop() {
        log.info("钉钉通道适配器停止");
    }

    @Override
    protected void doSendResponse(String userId, GatewayResponse response) {
        ResponseContent content = response.content();
        String text = converter.convert(content);
        if (converter.shouldUseActionCard(content)) {
            String title = content instanceof ResponseContent.CardContent card
                    ? card.title() : "LifePilot";
            apiClient.sendActionCard(userId, title, text);
        } else {
            apiClient.sendText(userId, text);
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────

    /**
     * 从钉钉消息 JSON 中提取文本内容。
     */
    @SuppressWarnings("unchecked")
    static String extractText(Map<String, Object> body) {
        var text = body.get("text");
        if (text instanceof Map<?, ?> textMap) {
            var content = textMap.get("content");
            return content != null ? content.toString().strip() : "";
        }
        return "";
    }

    /**
     * 去除钉钉消息中的 @ 提及前缀。
     *
     * <p>钉钉 @ 机器人的消息格式为 "@机器人名 实际内容"，需要去除 @ 部分。
     *
     * @param text 原始文本
     * @return 去除 @ 前缀后的文本
     */
    static String removeAtPrefix(String text) {
        if (text == null || text.isEmpty()) return "";
        // 去除所有 @xxx 提及（可能有多个）
        return text.replaceAll("@\\S+\\s*", "").strip();
    }

    private static String getStringField(Map<String, Object> map, String key, String defaultValue) {
        var value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
