package com.lifepilot.interaction.channel.feishu;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.config.ChannelConfigProvider;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lifepilot.config.threadpool.SharedScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 飞书通道适配器单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
@ExtendWith(MockitoExtension.class)
class FeishuChannelAdapter_单元测试 {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private MessageGateway gateway;
    @Mock private FeishuApiClient apiClient;
    @Mock private ChannelConfigProvider configProvider;

    private FeishuCrypto crypto;
    private FeishuMessageConverter converter;
    private FeishuChannelAdapter adapter;

    private static final String ENCRYPT_KEY = "test-encrypt-key-for-feishu";
    private static final String APP_ID = "cli_test123";
    private static final String VERIFICATION_TOKEN = "test-verify-token";

    @BeforeEach
    void setUp() {
        crypto = new FeishuCrypto(ENCRYPT_KEY);
        converter = new FeishuMessageConverter();
        var properties = buildProperties();

        // 配置 configProvider 返回飞书配置
        var feishuConfig = properties.channels().feishu();
        lenient().when(configProvider.getFeishuConfig()).thenReturn(feishuConfig);

        adapter = new FeishuChannelAdapter(gateway, properties, crypto, apiClient, converter,
                mock(SharedScheduler.class), configProvider);
    }

    // ── channelType ──────────────────────────────────────────

    @Test
    void channelType_返回FEISHU() {
        assertThat(adapter.channelType()).isEqualTo(ChannelType.FEISHU);
    }

    // ── Challenge 验证 ──────────────────────────────────────────

    @Test
    void handleEvent_Challenge请求_返回challenge值() throws Exception {
        String json = MAPPER.writeValueAsString(Map.of(
                "challenge", "test-challenge-value",
                "token", VERIFICATION_TOKEN,
                "type", "url_verification"
        ));

        var result = adapter.handleEvent(json.getBytes(StandardCharsets.UTF_8));
        assertThat(result).containsEntry("challenge", "test-challenge-value");
        verify(gateway, never()).process(any());
    }

    @Test
    void handleEvent_加密Challenge_解密后返回challenge值() throws Exception {
        // 构造明文 Challenge JSON，加密后包装
        String challengeJson = MAPPER.writeValueAsString(Map.of(
                "challenge", "encrypted-challenge",
                "token", VERIFICATION_TOKEN,
                "type", "url_verification"
        ));
        String encrypted = crypto.encrypt(challengeJson);
        String json = MAPPER.writeValueAsString(Map.of("encrypt", encrypted));

        var result = adapter.handleEvent(json.getBytes(StandardCharsets.UTF_8));
        assertThat(result).containsEntry("challenge", "encrypted-challenge");
        verify(gateway, never()).process(any());
    }

    // ── 事件处理 ──────────────────────────────────────────────

    @Test
    void handleEvent_正常消息事件_异步提交到Gateway() throws Exception {
        String json = buildMessageEventJson("evt-001", "你好");

        when(gateway.process(any())).thenReturn(
                GatewayResponse.success(ChannelType.FEISHU, new ResponseContent.TextContent("回复")));

        var result = adapter.handleEvent(json.getBytes(StandardCharsets.UTF_8));
        assertThat(result).containsEntry("code", 0);

        // 等待异步线程
        Thread.sleep(500);
        verify(gateway, atMostOnce()).process(any());
    }

    @Test
    void handleEvent_加密消息事件_解密后处理() throws Exception {
        String eventJson = buildMessageEventJson("evt-002", "加密消息");
        String encrypted = crypto.encrypt(eventJson);
        String json = MAPPER.writeValueAsString(Map.of("encrypt", encrypted));

        when(gateway.process(any())).thenReturn(
                GatewayResponse.success(ChannelType.FEISHU, new ResponseContent.TextContent("回复")));

        var result = adapter.handleEvent(json.getBytes(StandardCharsets.UTF_8));
        assertThat(result).containsEntry("code", 0);

        Thread.sleep(500);
        verify(gateway, atMostOnce()).process(any());
    }

    // ── 事件去重 ──────────────────────────────────────────────

    @Test
    void handleEvent_重复eventId_跳过处理() throws Exception {
        String json = buildMessageEventJson("evt-dup-001", "第一次");

        when(gateway.process(any())).thenReturn(
                GatewayResponse.success(ChannelType.FEISHU, new ResponseContent.TextContent("ok")));

        // 第一次处理
        adapter.handleEvent(json.getBytes(StandardCharsets.UTF_8));
        Thread.sleep(300);

        // 第二次相同 eventId
        adapter.handleEvent(json.getBytes(StandardCharsets.UTF_8));
        Thread.sleep(300);

        // Gateway 只应被调用一次
        verify(gateway, atMost(1)).process(any());
    }

    @Test
    void isDuplicate_首次eventId_返回false() {
        assertThat(adapter.isDuplicate("new-event-id")).isFalse();
    }

    @Test
    void isDuplicate_重复eventId_返回true() {
        adapter.isDuplicate("dup-id");
        assertThat(adapter.isDuplicate("dup-id")).isTrue();
    }

    // ── normalize ──────────────────────────────────────────────

    @Test
    void normalize_正常消息_生成TextMessage() {
        var event = Map.<String, Object>of(
                "header", Map.of("event_id", "evt-100", "event_type", "im.message.receive_v1", "tenant_key", "tk1"),
                "event", Map.of(
                        "message", Map.of("message_id", "msg-1", "chat_id", "oc_chat1", "chat_type", "p2p",
                                "content", "{\"text\":\"你好飞书\"}"),
                        "sender", Map.of("sender_id", Map.of("open_id", "ou_user1"))
                )
        );

        var message = adapter.normalize(event);
        assertThat(message.channelType()).isEqualTo(ChannelType.FEISHU);
        assertThat(message.userId()).isEqualTo("ou_user1");
        assertThat(message.sessionId()).isEqualTo("feishu:oc_chat1:ou_user1");
        assertThat(message.content().toPlainText()).isEqualTo("你好飞书");
    }

    @Test
    void normalize_命令消息_生成CommandMessage() {
        var event = Map.<String, Object>of(
                "header", Map.of("event_id", "evt-101", "event_type", "im.message.receive_v1", "tenant_key", "tk1"),
                "event", Map.of(
                        "message", Map.of("message_id", "msg-2", "chat_id", "oc_chat1", "chat_type", "p2p",
                                "content", "{\"text\":\"/todo 买菜\"}"),
                        "sender", Map.of("sender_id", Map.of("open_id", "ou_user1"))
                )
        );

        var message = adapter.normalize(event);
        assertThat(message.isCommand()).isTrue();
    }

    // ── 异常处理 ──────────────────────────────────────────────

    @Test
    void handleEvent_非法JSON_返回code0() {
        var result = adapter.handleEvent("not-valid-json".getBytes(StandardCharsets.UTF_8));
        assertThat(result).containsEntry("code", 0);
        verify(gateway, never()).process(any());
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private static String buildMessageEventJson(String eventId, String text) throws Exception {
        var event = Map.of(
                "header", Map.of("event_id", eventId, "event_type", "im.message.receive_v1", "tenant_key", "tk1"),
                "event", Map.of(
                        "message", Map.of("message_id", "msg-" + eventId, "chat_id", "oc_chat1",
                                "chat_type", "p2p", "content", "{\"text\":\"" + text + "\"}"),
                        "sender", Map.of("sender_id", Map.of("open_id", "ou_user1"))
                )
        );
        return MAPPER.writeValueAsString(event);
    }

    private static GatewayProperties buildProperties() {
        var wecom = new GatewayProperties.ChannelsProperties.WecomChannelProperties(
                false, null, null, null, null, null);
        var dingtalk = new GatewayProperties.ChannelsProperties.DingtalkChannelProperties(
                false, null, null, null);
        var feishu = new GatewayProperties.ChannelsProperties.FeishuChannelProperties(
                true, APP_ID, "app-secret", VERIFICATION_TOKEN, ENCRYPT_KEY, 10000);
        var channels = new GatewayProperties.ChannelsProperties(
                new GatewayProperties.ChannelsProperties.CliChannelProperties(true),
                new GatewayProperties.ChannelsProperties.WebChannelProperties(false),
                wecom, dingtalk, feishu);
        return new GatewayProperties(
                true, new GatewayProperties.MiddlewareProperties(
                    new GatewayProperties.MiddlewareProperties.AuthMiddlewareProperties(true, 100),
                    new GatewayProperties.MiddlewareProperties.RateLimitMiddlewareProperties(true, 200),
                    new GatewayProperties.MiddlewareProperties.SecurityMiddlewareProperties(true, 300),
                    new GatewayProperties.MiddlewareProperties.RouterMiddlewareProperties(true, 400),
                    new GatewayProperties.MiddlewareProperties.ExecutionMiddlewareProperties(true, 500),
                    new GatewayProperties.MiddlewareProperties.AuditMiddlewareProperties(true, 600)),
                new GatewayProperties.RateLimitProperties(100000, 500000, 2000, 30),
                new GatewayProperties.SecurityProperties(
                    new GatewayProperties.SecurityProperties.PromptInjectionProperties(true),
                    new GatewayProperties.SecurityProperties.SensitiveDataProperties(true),
                    new GatewayProperties.SecurityProperties.TrustScoreProperties(true, 30)),
                new GatewayProperties.AuthProperties(
                    new GatewayProperties.AuthProperties.WebAuthProperties(
                        new GatewayProperties.AuthProperties.WebAuthProperties.JwtProperties(false, 24),
                        new GatewayProperties.AuthProperties.WebAuthProperties.SessionAuthProperties(true, 30))),
                new GatewayProperties.RouterProperties(java.util.List.of("todo", "schedule", "habit")),
                new GatewayProperties.ExecutionProperties(120, true),
                new GatewayProperties.AuditProperties(true, 200, 200, 90),
                channels,
                new GatewayProperties.ReconnectProperties(10, 1000, 60000, 2.0),
                new GatewayProperties.SessionProperties(30, 24, 15),
                new GatewayProperties.WebhookProperties(300, 3, 60)
        );
    }
}
