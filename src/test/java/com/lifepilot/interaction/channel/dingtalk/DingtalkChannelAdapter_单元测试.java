package com.lifepilot.interaction.channel.dingtalk;

import java.util.Map;

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
 * 钉钉通道适配器单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
@ExtendWith(MockitoExtension.class)
class DingtalkChannelAdapter_单元测试 {

    @Mock private MessageGateway gateway;
    @Mock private DingtalkApiClient apiClient;

    private DingtalkSignatureVerifier verifier;
    private DingtalkMessageConverter converter;
    private DingtalkChannelAdapter adapter;
    private GatewayProperties properties;

    private static final String APP_SECRET = "test-app-secret-key";
    private static final String ROBOT_CODE = "test-robot";

    @BeforeEach
    void setUp() {
        verifier = new DingtalkSignatureVerifier();
        converter = new DingtalkMessageConverter();
        properties = buildProperties();
        adapter = new DingtalkChannelAdapter(gateway, properties, verifier, apiClient, converter, mock(SharedScheduler.class));
    }

    // ── channelType ──────────────────────────────────────────

    @Test
    void channelType_返回DINGTALK() {
        assertThat(adapter.channelType()).isEqualTo(ChannelType.DINGTALK);
    }

    // ── 消息处理 ──────────────────────────────────────────────

    @Test
    void handleMessage_签名正确_同步返回text响应() {
        long timestamp = System.currentTimeMillis();
        String sign = verifier.compute(timestamp, APP_SECRET);

        var headers = Map.of("sign", sign, "timestamp", String.valueOf(timestamp));
        String jsonBody = """
                {"msgtype":"text","text":{"content":"你好"},"conversationId":"conv1","senderStaffId":"user1","msgId":"m1"}
                """;

        when(gateway.process(any())).thenReturn(
                GatewayResponse.success(ChannelType.DINGTALK, new ResponseContent.TextContent("回复")));

        var result = adapter.handleMessage(headers, jsonBody);
        assertThat(result).containsEntry("msgtype", "text");
        verify(gateway).process(any());
    }

    @Test
    void handleMessage_签名失败_返回错误() {
        var headers = Map.of("sign", "wrong-sign", "timestamp", String.valueOf(System.currentTimeMillis()));
        String jsonBody = """
                {"msgtype":"text","text":{"content":"hello"}}
                """;

        var result = adapter.handleMessage(headers, jsonBody);
        assertThat(result).containsKey("errcode");
        verify(gateway, never()).process(any());
    }

    @Test
    void handleMessage_缺少签名参数_返回空Map() {
        var headers = Map.of("other", "value");
        String jsonBody = """
                {"msgtype":"text","text":{"content":"hello"}}
                """;

        var result = adapter.handleMessage(headers, jsonBody);
        assertThat(result).isEmpty();
        verify(gateway, never()).process(any());
    }

    @Test
    void handleMessage_时间戳过期_返回空Map() {
        long expiredTimestamp = System.currentTimeMillis() - 600_000; // 10 分钟前
        String sign = verifier.compute(expiredTimestamp, APP_SECRET);

        var headers = Map.of("sign", sign, "timestamp", String.valueOf(expiredTimestamp));
        String jsonBody = """
                {"msgtype":"text","text":{"content":"hello"}}
                """;

        var result = adapter.handleMessage(headers, jsonBody);
        assertThat(result).isEmpty();
        verify(gateway, never()).process(any());
    }

    @Test
    void handleMessage_长文本响应_使用ActionCard() {
        long timestamp = System.currentTimeMillis();
        String sign = verifier.compute(timestamp, APP_SECRET);

        var headers = Map.of("sign", sign, "timestamp", String.valueOf(timestamp));
        String jsonBody = """
                {"msgtype":"text","text":{"content":"查询"},"conversationId":"conv1","senderStaffId":"user1","msgId":"m2"}
                """;

        // 返回 Markdown 内容，触发 ActionCard
        when(gateway.process(any())).thenReturn(
                GatewayResponse.success(ChannelType.DINGTALK, new ResponseContent.MarkdownContent("# 标题\n内容")));

        var result = adapter.handleMessage(headers, jsonBody);
        assertThat(result).containsEntry("msgtype", "actionCard");
    }

    // ── normalize ──────────────────────────────────────────────

    @Test
    void normalize_正常消息_生成TextMessage() {
        var body = Map.<String, Object>of(
                "senderStaffId", "user1",
                "conversationId", "conv1",
                "conversationType", "1",
                "senderNick", "张三",
                "msgId", "m1",
                "text", Map.of("content", "你好")
        );

        var message = adapter.normalize(body);
        assertThat(message.channelType()).isEqualTo(ChannelType.DINGTALK);
        assertThat(message.userId()).isEqualTo("user1");
        assertThat(message.sessionId()).isEqualTo("dingtalk:conv1");
        assertThat(message.content().toPlainText()).isEqualTo("你好");
    }

    @Test
    void normalize_命令消息_生成CommandMessage() {
        var body = Map.<String, Object>of(
                "senderStaffId", "user1",
                "conversationId", "conv1",
                "text", Map.of("content", "/todo 买菜")
        );

        var message = adapter.normalize(body);
        assertThat(message.isCommand()).isTrue();
    }

    // ── @ 前缀去除 ──────────────────────────────────────────

    @Test
    void removeAtPrefix_去除单个At() {
        assertThat(DingtalkChannelAdapter.removeAtPrefix("@机器人 你好")).isEqualTo("你好");
    }

    @Test
    void removeAtPrefix_去除多个At() {
        assertThat(DingtalkChannelAdapter.removeAtPrefix("@bot1 @bot2 你好")).isEqualTo("你好");
    }

    @Test
    void removeAtPrefix_无At_原样返回() {
        assertThat(DingtalkChannelAdapter.removeAtPrefix("你好")).isEqualTo("你好");
    }

    @Test
    void removeAtPrefix_空字符串_返回空() {
        assertThat(DingtalkChannelAdapter.removeAtPrefix("")).isEmpty();
    }

    @Test
    void removeAtPrefix_null_返回空() {
        assertThat(DingtalkChannelAdapter.removeAtPrefix(null)).isEmpty();
    }

    // ── extractText ──────────────────────────────────────────

    @Test
    void extractText_正常text字段_提取成功() {
        var body = Map.<String, Object>of("text", Map.of("content", " hello "));
        assertThat(DingtalkChannelAdapter.extractText(body)).isEqualTo("hello");
    }

    @Test
    void extractText_无text字段_返回空() {
        var body = Map.<String, Object>of("other", "value");
        assertThat(DingtalkChannelAdapter.extractText(body)).isEmpty();
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private static GatewayProperties buildProperties() {
        var wecom = new GatewayProperties.ChannelsProperties.WecomChannelProperties(
                false, null, null, null, null, null);
        var dingtalk = new GatewayProperties.ChannelsProperties.DingtalkChannelProperties(
                true, "app-key", APP_SECRET, ROBOT_CODE);
        var feishu = new GatewayProperties.ChannelsProperties.FeishuChannelProperties(
                false, null, null, null, null, 10000);
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
