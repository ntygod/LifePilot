package com.lifepilot.interaction.channel;

import java.nio.file.Path;
import java.util.UUID;

import com.lifepilot.interaction.channel.dingtalk.DingtalkChannelAdapter;
import com.lifepilot.interaction.channel.feishu.FeishuChannelAdapter;
import com.lifepilot.interaction.channel.wecom.WecomChannelAdapter;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.model.ChannelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通道适配器 → Gateway 集成测试。
 *
 * <p>验证通道适配器注册到 Gateway、生命周期管理、状态转换。
 * 不测试端到端消息流（Auth 中间件会拒绝无签名消息），
 * 端到端消息流由 WebhookController 集成测试通过 HTTP 端点验证。
 *
 * @author zsg
 * @since 2026-02-26
 */
@SpringBootTest
@ActiveProfiles("test")
class ChannelAdapter_Gateway_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-gw-e2e-" + DB_ID + ".db").toString().replace("\\", "/"));
        registry.add("lifepilot.memory.vector-db-url",
                () -> "jdbc:sqlite:" + Path.of(tmpDir, "ca-gw-e2e-vec-" + DB_ID + ".db").toString().replace("\\", "/"));
        // 启用所有三个通道
        registry.add("lifepilot.gateway.channels.wecom.enabled", () -> "true");
        registry.add("lifepilot.gateway.channels.wecom.corp-id", () -> "test-corp");
        registry.add("lifepilot.gateway.channels.wecom.agent-id", () -> "1000001");
        registry.add("lifepilot.gateway.channels.wecom.secret", () -> "test-secret");
        registry.add("lifepilot.gateway.channels.wecom.token", () -> "test-token");
        registry.add("lifepilot.gateway.channels.wecom.encoding-aes-key",
                () -> "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
        registry.add("lifepilot.gateway.channels.dingtalk.enabled", () -> "true");
        registry.add("lifepilot.gateway.channels.dingtalk.app-key", () -> "test-app-key");
        registry.add("lifepilot.gateway.channels.dingtalk.app-secret", () -> "test-app-secret");
        registry.add("lifepilot.gateway.channels.dingtalk.robot-code", () -> "test-robot");
        registry.add("lifepilot.gateway.channels.feishu.enabled", () -> "true");
        registry.add("lifepilot.gateway.channels.feishu.app-id", () -> "test-app-id");
        registry.add("lifepilot.gateway.channels.feishu.app-secret", () -> "test-app-secret");
        registry.add("lifepilot.gateway.channels.feishu.verification-token", () -> "test-verify-token");
        registry.add("lifepilot.gateway.channels.feishu.encrypt-key", () -> "test-encrypt-key-value");
    }

    @Autowired
    MessageGateway gateway;

    @BeforeEach
    void setUp() {
        gateway.start();
    }

    // ── 通道注册验证 ──────────────────────────────────────────

    @Test
    void 三个通道适配器_均已注册到Gateway() {
        assertThat(gateway.getChannel(ChannelType.WECOM)).isPresent();
        assertThat(gateway.getChannel(ChannelType.DINGTALK)).isPresent();
        assertThat(gateway.getChannel(ChannelType.FEISHU)).isPresent();
    }

    @Test
    void Gateway已注册通道列表_包含三个Webhook通道() {
        var channels = gateway.getAllChannels();
        var types = channels.stream().map(ChannelAdapter::channelType).toList();
        assertThat(types).contains(ChannelType.WECOM, ChannelType.DINGTALK, ChannelType.FEISHU);
    }

    // ── 通道适配器状态验证 ──────────────────────────────────────

    @Test
    void 通道适配器_启动后状态为RUNNING() {
        var wecom = gateway.getChannel(ChannelType.WECOM).orElseThrow();
        var dingtalk = gateway.getChannel(ChannelType.DINGTALK).orElseThrow();
        var feishu = gateway.getChannel(ChannelType.FEISHU).orElseThrow();

        assertThat(((WecomChannelAdapter) wecom).getState()).isEqualTo(ChannelState.RUNNING);
        assertThat(((DingtalkChannelAdapter) dingtalk).getState()).isEqualTo(ChannelState.RUNNING);
        assertThat(((FeishuChannelAdapter) feishu).getState()).isEqualTo(ChannelState.RUNNING);
    }

    @Test
    void 各通道适配器_channelType正确() {
        var wecom = gateway.getChannel(ChannelType.WECOM).orElseThrow();
        var dingtalk = gateway.getChannel(ChannelType.DINGTALK).orElseThrow();
        var feishu = gateway.getChannel(ChannelType.FEISHU).orElseThrow();

        assertThat(wecom.channelType()).isEqualTo(ChannelType.WECOM);
        assertThat(dingtalk.channelType()).isEqualTo(ChannelType.DINGTALK);
        assertThat(feishu.channelType()).isEqualTo(ChannelType.FEISHU);
    }
}
