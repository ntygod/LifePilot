package com.lifepilot.interaction.channel.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.lifepilot.interaction.channel.dingtalk.DingtalkChannelAdapter;
import com.lifepilot.interaction.channel.feishu.FeishuChannelAdapter;
import com.lifepilot.interaction.channel.wecom.WecomChannelAdapter;
import com.lifepilot.interaction.config.ChannelConfigProvider;
import com.lifepilot.interaction.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WebhookController 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
@ExtendWith(MockitoExtension.class)
class WebhookController_单元测试 {

    @Mock private WecomChannelAdapter wecomAdapter;
    @Mock private DingtalkChannelAdapter dingtalkAdapter;
    @Mock private FeishuChannelAdapter feishuAdapter;
    @Mock private ChannelConfigProvider configProvider;

    /** 默认启用所有通道的配置 */
    private static final GatewayProperties.ChannelsProperties.WecomChannelProperties WECOM_ENABLED =
            new GatewayProperties.ChannelsProperties.WecomChannelProperties(true, "corp", "agent", "secret", "token", "aesKey");
    private static final GatewayProperties.ChannelsProperties.DingtalkChannelProperties DINGTALK_ENABLED =
            new GatewayProperties.ChannelsProperties.DingtalkChannelProperties(true, "key", "secret", "robot");
    private static final GatewayProperties.ChannelsProperties.FeishuChannelProperties FEISHU_ENABLED =
            new GatewayProperties.ChannelsProperties.FeishuChannelProperties(true, "appId", "secret", "token", "encKey", 10000);

    private static final GatewayProperties.ChannelsProperties.WecomChannelProperties WECOM_DISABLED =
            new GatewayProperties.ChannelsProperties.WecomChannelProperties(false, null, null, null, null, null);
    private static final GatewayProperties.ChannelsProperties.DingtalkChannelProperties DINGTALK_DISABLED =
            new GatewayProperties.ChannelsProperties.DingtalkChannelProperties(false, null, null, null);
    private static final GatewayProperties.ChannelsProperties.FeishuChannelProperties FEISHU_DISABLED =
            new GatewayProperties.ChannelsProperties.FeishuChannelProperties(false, null, null, null, null, 10000);

    @BeforeEach
    void setUp() {
        // 默认所有通道启用
        lenient().when(configProvider.getWecomConfig()).thenReturn(WECOM_ENABLED);
        lenient().when(configProvider.getDingtalkConfig()).thenReturn(DINGTALK_ENABLED);
        lenient().when(configProvider.getFeishuConfig()).thenReturn(FEISHU_ENABLED);
    }

    // ── 企微路由 ──────────────────────────────────────────────

    @Test
    void wecomVerify_适配器存在_转发到适配器() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        var params = Map.of("msg_signature", "sig", "timestamp", "123", "nonce", "n", "echostr", "echo");
        when(wecomAdapter.handleVerification(params)).thenReturn("decrypted-echo");

        String result = controller.wecomVerify(params);
        assertThat(result).isEqualTo("decrypted-echo");
        verify(wecomAdapter).handleVerification(params);
    }

    @Test
    void wecomVerify_通道未启用_返回success() {
        when(configProvider.getWecomConfig()).thenReturn(WECOM_DISABLED);
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        String result = controller.wecomVerify(Map.of());
        assertThat(result).isEqualTo("success");
    }

    @Test
    void wecomVerify_适配器抛异常_返回success() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        when(wecomAdapter.handleVerification(any())).thenThrow(new RuntimeException("测试异常"));

        String result = controller.wecomVerify(Map.of());
        assertThat(result).isEqualTo("success");
    }

    @Test
    void wecomMessage_适配器存在_转发到适配器() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        var params = Map.of("msg_signature", "sig", "timestamp", "123", "nonce", "n");
        when(wecomAdapter.handleMessage(params, "<xml/>")).thenReturn("success");

        String result = controller.wecomMessage(params, "<xml/>");
        assertThat(result).isEqualTo("success");
    }

    @Test
    void wecomMessage_通道未启用_返回success() {
        when(configProvider.getWecomConfig()).thenReturn(WECOM_DISABLED);
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        String result = controller.wecomMessage(Map.of(), "<xml/>");
        assertThat(result).isEqualTo("success");
    }

    @Test
    void wecomMessage_适配器抛异常_返回success() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        when(wecomAdapter.handleMessage(any(), anyString())).thenThrow(new RuntimeException("测试异常"));

        String result = controller.wecomMessage(Map.of(), "<xml/>");
        assertThat(result).isEqualTo("success");
    }

    // ── 钉钉路由 ──────────────────────────────────────────────

    @Test
    void dingtalkMessage_适配器存在_转发到适配器() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        var headers = Map.of("sign", "s", "timestamp", "123");
        when(dingtalkAdapter.handleMessage(headers, "{}")).thenReturn(Map.of("msgtype", "text"));

        var result = controller.dingtalkMessage(headers, "{}");
        assertThat(result).containsEntry("msgtype", "text");
    }

    @Test
    void dingtalkMessage_通道未启用_返回空Map() {
        when(configProvider.getDingtalkConfig()).thenReturn(DINGTALK_DISABLED);
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        var result = controller.dingtalkMessage(Map.of(), "{}");
        assertThat(result).isEmpty();
    }

    @Test
    void dingtalkMessage_适配器抛异常_返回空Map() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        when(dingtalkAdapter.handleMessage(any(), anyString())).thenThrow(new RuntimeException("测试异常"));

        var result = controller.dingtalkMessage(Map.of(), "{}");
        assertThat(result).isEmpty();
    }

    // ── 飞书路由 ──────────────────────────────────────────────

    @Test
    void feishuEvent_适配器存在_转发到适配器() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        when(feishuAdapter.handleEvent(body)).thenReturn(Map.of("code", 0));

        var result = controller.feishuEvent(body);
        assertThat(result).containsEntry("code", 0);
    }

    @Test
    void feishuEvent_通道未启用_返回code0() {
        when(configProvider.getFeishuConfig()).thenReturn(FEISHU_DISABLED);
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        var result = controller.feishuEvent("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(result).containsEntry("code", 0);
    }

    @Test
    void feishuEvent_适配器抛异常_返回code0() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);
        when(feishuAdapter.handleEvent(any(byte[].class))).thenThrow(new RuntimeException("测试异常"));

        var result = controller.feishuEvent("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(result).containsEntry("code", 0);
    }

    // ── 全部通道未启用 ──────────────────────────────────────────

    @Test
    void 所有通道未启用_各端点返回默认响应() {
        when(configProvider.getWecomConfig()).thenReturn(WECOM_DISABLED);
        when(configProvider.getDingtalkConfig()).thenReturn(DINGTALK_DISABLED);
        when(configProvider.getFeishuConfig()).thenReturn(FEISHU_DISABLED);
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter, configProvider);

        assertThat(controller.wecomVerify(Map.of())).isEqualTo("success");
        assertThat(controller.wecomMessage(Map.of(), "")).isEqualTo("success");
        assertThat(controller.dingtalkMessage(Map.of(), "")).isEmpty();
        assertThat(controller.feishuEvent("".getBytes(StandardCharsets.UTF_8))).containsEntry("code", 0);
    }
}
