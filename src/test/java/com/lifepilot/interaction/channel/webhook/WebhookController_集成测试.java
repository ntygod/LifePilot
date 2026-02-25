package com.lifepilot.interaction.channel.webhook;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebhookController HTTP 端点集成测试，使用 MockMvc 验证端点注册和路由分发。
 *
 * <p>启用企微通道以确保 WebhookController 被注册。
 * 测试重点：端点可达性、异常时返回平台要求的成功响应。
 *
 * @author zsg
 * @since 2026-02-26
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WebhookController_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + Path.of(tmpDir, "wh-ctrl-it-" + DB_ID + ".db").toString().replace("\\", "/"));
        registry.add("lifepilot.memory.vector-db-url",
                () -> "jdbc:sqlite:" + Path.of(tmpDir, "wh-ctrl-it-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
        // 启用企微通道以注册 WebhookController
        registry.add("lifepilot.gateway.channels.wecom.enabled", () -> "true");
        registry.add("lifepilot.gateway.channels.wecom.corp-id", () -> "test-corp");
        registry.add("lifepilot.gateway.channels.wecom.agent-id", () -> "1000001");
        registry.add("lifepilot.gateway.channels.wecom.secret", () -> "test-secret");
        registry.add("lifepilot.gateway.channels.wecom.token", () -> "test-token");
        registry.add("lifepilot.gateway.channels.wecom.encoding-aes-key",
                () -> "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
        // 启用钉钉通道
        registry.add("lifepilot.gateway.channels.dingtalk.enabled", () -> "true");
        registry.add("lifepilot.gateway.channels.dingtalk.app-key", () -> "test-app-key");
        registry.add("lifepilot.gateway.channels.dingtalk.app-secret", () -> "test-app-secret");
        registry.add("lifepilot.gateway.channels.dingtalk.robot-code", () -> "test-robot");
        // 启用飞书通道
        registry.add("lifepilot.gateway.channels.feishu.enabled", () -> "true");
        registry.add("lifepilot.gateway.channels.feishu.app-id", () -> "test-app-id");
        registry.add("lifepilot.gateway.channels.feishu.app-secret", () -> "test-app-secret");
        registry.add("lifepilot.gateway.channels.feishu.verification-token", () -> "test-verify-token");
        registry.add("lifepilot.gateway.channels.feishu.encrypt-key", () -> "test-encrypt-key-value");
    }

    @Autowired
    MockMvc mockMvc;

    // ── 企微端点测试 ──────────────────────────────────────────

    @Test
    void 企微GET验证端点_签名失败_返回success() throws Exception {
        mockMvc.perform(get("/api/webhook/wecom")
                        .param("msg_signature", "invalid-sig")
                        .param("timestamp", "1234567890")
                        .param("nonce", "test-nonce")
                        .param("echostr", "test-echostr"))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));
    }

    @Test
    void 企微POST消息端点_无效XML_返回success() throws Exception {
        mockMvc.perform(post("/api/webhook/wecom")
                        .param("msg_signature", "invalid-sig")
                        .param("timestamp", "1234567890")
                        .param("nonce", "test-nonce")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<xml><Invalid>data</Invalid></xml>"))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));
    }

    // ── 钉钉端点测试 ──────────────────────────────────────────

    @Test
    void 钉钉POST消息端点_签名失败_返回空JSON() throws Exception {
        mockMvc.perform(post("/api/webhook/dingtalk")
                        .header("sign", "invalid-sign")
                        .header("timestamp", String.valueOf(System.currentTimeMillis()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":{\"content\":\"hello\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errcode").value(401));
    }

    @Test
    void 钉钉POST消息端点_缺少签名头_返回空JSON() throws Exception {
        mockMvc.perform(post("/api/webhook/dingtalk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":{\"content\":\"hello\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
    }

    // ── 飞书端点测试 ──────────────────────────────────────────

    @Test
    void 飞书POST_Challenge验证_返回challenge() throws Exception {
        mockMvc.perform(post("/api/webhook/feishu")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge\":\"test-challenge-token\",\"type\":\"url_verification\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("test-challenge-token"));
    }

    @Test
    void 飞书POST_无效JSON_返回code0() throws Exception {
        mockMvc.perform(post("/api/webhook/feishu")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-valid-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ── 端点可达性测试 ──────────────────────────────────────────

    @Test
    void 所有Webhook端点_均已注册() throws Exception {
        // 企微 GET
        mockMvc.perform(get("/api/webhook/wecom"))
                .andExpect(status().isOk());

        // 企微 POST
        mockMvc.perform(post("/api/webhook/wecom")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<xml></xml>"))
                .andExpect(status().isOk());

        // 钉钉 POST
        mockMvc.perform(post("/api/webhook/dingtalk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // 飞书 POST
        mockMvc.perform(post("/api/webhook/feishu")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}
