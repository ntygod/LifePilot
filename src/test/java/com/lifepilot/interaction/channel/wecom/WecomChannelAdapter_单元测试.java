package com.lifepilot.interaction.channel.wecom;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 企业微信通道适配器单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
@ExtendWith(MockitoExtension.class)
class WecomChannelAdapter_单元测试 {

    @Mock private MessageGateway gateway;
    @Mock private WecomApiClient apiClient;

    private WecomCrypto crypto;
    private WecomSignatureVerifier verifier;
    private WecomMessageConverter converter;
    private WecomChannelAdapter adapter;
    private GatewayProperties properties;

    private static final String CORP_ID = "test-corp";
    private static final String AGENT_ID = "1000001";
    private static final String TOKEN = "test-token";
    private static final String ENCODING_AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    @BeforeEach
    void setUp() {
        crypto = new WecomCrypto(ENCODING_AES_KEY, CORP_ID);
        verifier = new WecomSignatureVerifier(TOKEN);
        converter = new WecomMessageConverter();
        properties = buildProperties();
        adapter = new WecomChannelAdapter(gateway, properties, crypto, verifier, apiClient, converter);
    }

    // ── channelType ──────────────────────────────────────────

    @Test
    void channelType_返回WECOM() {
        assertThat(adapter.channelType()).isEqualTo(ChannelType.WECOM);
    }

    // ── URL 验证 ──────────────────────────────────────────────

    @Test
    void handleVerification_签名正确_返回解密echostr() {
        String echostr = crypto.encrypt("test-echostr");
        String timestamp = "1234567890";
        String nonce = "test-nonce";
        String signature = verifier.compute(timestamp, nonce, echostr);

        var params = Map.of(
                "msg_signature", signature,
                "timestamp", timestamp,
                "nonce", nonce,
                "echostr", echostr
        );

        String result = adapter.handleVerification(params);
        assertThat(result).isEqualTo("test-echostr");
    }

    @Test
    void handleVerification_签名错误_返回success() {
        var params = Map.of(
                "msg_signature", "wrong-signature",
                "timestamp", "1234567890",
                "nonce", "test-nonce",
                "echostr", "some-echostr"
        );

        String result = adapter.handleVerification(params);
        assertThat(result).isEqualTo("success");
    }

    // ── 消息处理 ──────────────────────────────────────────────

    @Test
    void handleMessage_签名正确_异步提交到Gateway() throws InterruptedException {
        // 构造加密 XML
        String plainXml = "<xml><ToUserName>corp</ToUserName><FromUserName>user1</FromUserName>"
                + "<MsgType>text</MsgType><Content>你好</Content><MsgId>123</MsgId></xml>";
        String encrypted = crypto.encrypt(plainXml);
        String outerXml = "<xml><Encrypt><![CDATA[" + encrypted + "]]></Encrypt></xml>";

        String timestamp = "1234567890";
        String nonce = "test-nonce";
        String signature = verifier.compute(timestamp, nonce, encrypted);

        var params = Map.of(
                "msg_signature", signature,
                "timestamp", timestamp,
                "nonce", nonce
        );

        // 模拟 Gateway 返回
        when(gateway.process(any())).thenReturn(
                GatewayResponse.success(ChannelType.WECOM, new ResponseContent.TextContent("回复")));

        String result = adapter.handleMessage(params, outerXml);
        assertThat(result).isEqualTo("success");

        // 等待异步线程执行
        Thread.sleep(500);
        verify(gateway, atMostOnce()).process(any());
    }

    @Test
    void handleMessage_签名失败_返回success不提交() {
        String outerXml = "<xml><Encrypt><![CDATA[some-encrypted]]></Encrypt></xml>";
        var params = Map.of(
                "msg_signature", "wrong",
                "timestamp", "1234567890",
                "nonce", "test-nonce"
        );

        String result = adapter.handleMessage(params, outerXml);
        assertThat(result).isEqualTo("success");
        verify(gateway, never()).process(any());
    }

    @Test
    void handleMessage_缺少Encrypt字段_返回success() {
        String xmlBody = "<xml><Content>hello</Content></xml>";
        var params = Map.of(
                "msg_signature", "sig",
                "timestamp", "123",
                "nonce", "nonce"
        );

        String result = adapter.handleMessage(params, xmlBody);
        assertThat(result).isEqualTo("success");
        verify(gateway, never()).process(any());
    }

    // ── normalize ──────────────────────────────────────────────

    @Test
    void normalize_文本消息_生成TextMessage() {
        var fields = Map.of(
                "FromUserName", "user1",
                "Content", "你好世界",
                "MsgId", "msg-001"
        );

        var message = adapter.normalize(fields);
        assertThat(message.channelType()).isEqualTo(ChannelType.WECOM);
        assertThat(message.userId()).isEqualTo("user1");
        assertThat(message.sessionId()).isEqualTo("wecom:test-corp:user1");
        assertThat(message.content().toPlainText()).isEqualTo("你好世界");
        assertThat(message.messageId()).isEqualTo("msg-001");
    }

    @Test
    void normalize_斜杠开头_生成CommandMessage() {
        var fields = Map.of(
                "FromUserName", "user1",
                "Content", "/todo 买菜",
                "MsgId", "msg-002"
        );

        var message = adapter.normalize(fields);
        assertThat(message.isCommand()).isTrue();
        assertThat(message.content().toPlainText()).isEqualTo("/todo 买菜");
    }

    @Test
    void normalize_空内容_生成空消息占位() {
        var fields = Map.of(
                "FromUserName", "user1",
                "Content", "",
                "MsgId", "msg-003"
        );

        var message = adapter.normalize(fields);
        assertThat(message.content().toPlainText()).isEqualTo("[空消息]");
    }

    // ── XML 解析边界 ──────────────────────────────────────────

    @Test
    void extractXmlField_正常XML_提取成功() {
        String xml = "<xml><Encrypt>abc123</Encrypt></xml>";
        assertThat(WecomChannelAdapter.extractXmlField(xml, "Encrypt")).isEqualTo("abc123");
    }

    @Test
    void extractXmlField_字段不存在_返回null() {
        String xml = "<xml><Other>value</Other></xml>";
        assertThat(WecomChannelAdapter.extractXmlField(xml, "Encrypt")).isNull();
    }

    @Test
    void extractXmlField_非法XML_返回null() {
        assertThat(WecomChannelAdapter.extractXmlField("not-xml", "Encrypt")).isNull();
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private static GatewayProperties buildProperties() {
        var wecom = new GatewayProperties.ChannelsProperties.WecomChannelProperties(
                true, CORP_ID, AGENT_ID, "secret", TOKEN, ENCODING_AES_KEY);
        var dingtalk = new GatewayProperties.ChannelsProperties.DingtalkChannelProperties(
                false, null, null, null);
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
