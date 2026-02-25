package com.lifepilot.interaction.channel.webhook;

import java.util.Map;

import com.lifepilot.interaction.channel.dingtalk.DingtalkChannelAdapter;
import com.lifepilot.interaction.channel.feishu.FeishuChannelAdapter;
import com.lifepilot.interaction.channel.wecom.WecomChannelAdapter;
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

    // ── 企微路由 ──────────────────────────────────────────────

    @Test
    void wecomVerify_适配器存在_转发到适配器() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter);
        var params = Map.of("msg_signature", "sig", "timestamp", "123", "nonce", "n", "echostr", "echo");
        when(wecomAdapter.handleVerification(params)).thenReturn("decrypted-echo");

        String result = controller.wecomVerify(params);
        assertThat(result).isEqualTo("decrypted-echo");
        verify(wecomAdapter).handleVerification(params);
    }

    @Test
    void wecomVerify_适配器为null_返回success() {
        var controller = new WebhookController(null, dingtalkAdapter, feishuAdapter);
        String result = controller.wecomVerify(Map.of());
        assertThat(result).isEqualTo("success");
    }

    @Test
    void wecomVerify_适配器抛异常_返回success() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter);
        when(wecomAdapter.handleVerification(any())).thenThrow(new RuntimeException("测试异常"));

        String result = controller.wecomVerify(Map.of());
        assertThat(result).isEqualTo("success");
    }

    @Test
    void wecomMessage_适配器存在_转发到适配器() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter);
        var params = Map.of("msg_signature", "sig", "timestamp", "123", "nonce", "n");
        when(wecomAdapter.handleMessage(params, "<xml/>")).thenReturn("success");

        String result = controller.wecomMessage(params, "<xml/>");
        assertThat(result).isEqualTo("success");
    }

    @Test
    void wecomMessage_适配器为null_返回success() {
        var controller = new WebhookController(null, dingtalkAdapter, feishuAdapter);
        String result = controller.wecomMessage(Map.of(), "<xml/>");
        assertThat(result).isEqualTo("success");
    }

    @Test
    void wecomMessage_适配器抛异常_返回success() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter);
        when(wecomAdapter.handleMessage(any(), anyString())).thenThrow(new RuntimeException("测试异常"));

        String result = controller.wecomMessage(Map.of(), "<xml/>");
        assertThat(result).isEqualTo("success");
    }

    // ── 钉钉路由 ──────────────────────────────────────────────

    @Test
    void dingtalkMessage_适配器存在_转发到适配器() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter);
        var headers = Map.of("sign", "s", "timestamp", "123");
        when(dingtalkAdapter.handleMessage(headers, "{}")).thenReturn(Map.of("msgtype", "text"));

        var result = controller.dingtalkMessage(headers, "{}");
        assertThat(result).containsEntry("msgtype", "text");
    }

    @Test
    void dingtalkMessage_适配器为null_返回空Map() {
        var controller = new WebhookController(wecomAdapter, null, feishuAdapter);
        var result = controller.dingtalkMessage(Map.of(), "{}");
        assertThat(result).isEmpty();
    }

    @Test
    void dingtalkMessage_适配器抛异常_返回空Map() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter);
        when(dingtalkAdapter.handleMessage(any(), anyString())).thenThrow(new RuntimeException("测试异常"));

        var result = controller.dingtalkMessage(Map.of(), "{}");
        assertThat(result).isEmpty();
    }

    // ── 飞书路由 ──────────────────────────────────────────────

    @Test
    void feishuEvent_适配器存在_转发到适配器() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter);
        when(feishuAdapter.handleEvent("{}")).thenReturn(Map.of("code", 0));

        var result = controller.feishuEvent("{}");
        assertThat(result).containsEntry("code", 0);
    }

    @Test
    void feishuEvent_适配器为null_返回code0() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, null);
        var result = controller.feishuEvent("{}");
        assertThat(result).containsEntry("code", 0);
    }

    @Test
    void feishuEvent_适配器抛异常_返回code0() {
        var controller = new WebhookController(wecomAdapter, dingtalkAdapter, feishuAdapter);
        when(feishuAdapter.handleEvent(anyString())).thenThrow(new RuntimeException("测试异常"));

        var result = controller.feishuEvent("{}");
        assertThat(result).containsEntry("code", 0);
    }

    // ── 全部通道为null ──────────────────────────────────────────

    @Test
    void 所有通道为null_各端点返回默认响应() {
        var controller = new WebhookController(null, null, null);

        assertThat(controller.wecomVerify(Map.of())).isEqualTo("success");
        assertThat(controller.wecomMessage(Map.of(), "")).isEqualTo("success");
        assertThat(controller.dingtalkMessage(Map.of(), "")).isEmpty();
        assertThat(controller.feishuEvent("")).containsEntry("code", 0);
    }
}
