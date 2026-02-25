package com.lifepilot.interaction.gateway;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewarePipeline;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 端到端管道集成测试。
 *
 * <p>验证完整中间件管道执行：Audit(50) → Auth(100) → RateLimit(200) → Security(300) → Router(400)。
 * 测试环境未注册 AgentLoop，因此 ExecutionMiddleware 不存在，自然语言消息会触发链耗尽。
 *
 * @author zsg
 * @since 2026-02-25
 */
@SpringBootTest
@ActiveProfiles("test")
class GatewayPipeline_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "gw-pipeline-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "gw-pipeline-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private MessageGateway gateway;

    @Autowired
    private MiddlewarePipeline pipeline;

    @BeforeEach
    void setUp() {
        gateway.start();
    }

    // ── 快速路径测试 ──────────────────────────────────────────────

    @Test
    void 已知命令_快速路径返回200() {
        var message = buildCliMessage(new MessageContent.CommandMessage("todo", List.of(), "/todo"));
        var response = gateway.process(message);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.tokenUsage()).isEqualTo(TokenUsage.ZERO);
        assertThat(response.content().toPlainText()).contains("todo");
    }

    @Test
    void 斜杠文本命令_快速路径返回200() {
        var message = buildCliMessage(new MessageContent.TextMessage("/schedule"));
        var response = gateway.process(message);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.tokenUsage()).isEqualTo(TokenUsage.ZERO);
    }

    @Test
    void 未知命令_返回400() {
        var message = buildCliMessage(new MessageContent.CommandMessage("unknown", List.of(), "/unknown"));
        var response = gateway.process(message);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.content().toPlainText()).contains("未知命令");
    }

    // ── 自然语言路径测试 ──────────────────────────────────────────

    @Test
    void 自然语言消息_无ExecutionMiddleware_链耗尽返回500() {
        // 测试环境未注册 AgentLoop，ExecutionMiddleware 不存在
        // 自然语言消息经过 Router 后调用 chain.next()，链耗尽返回 500
        var message = buildCliMessage(new MessageContent.TextMessage("你好"));
        var response = gateway.process(message);

        assertThat(response.statusCode()).isEqualTo(500);
    }

    // ── 中间件排序验证 ──────────────────────────────────────────

    @Test
    void 中间件按order排序_Audit最先Router最后() {
        var middlewares = pipeline.getMiddlewares();
        var orders = middlewares.stream()
                .map(GatewayMiddleware::order)
                .toList();

        // 验证排序：每个 order 不大于下一个
        for (int i = 0; i < orders.size() - 1; i++) {
            assertThat(orders.get(i))
                    .as("中间件 %s(order=%d) 应在 %s(order=%d) 之前",
                            middlewares.get(i).name(), orders.get(i),
                            middlewares.get(i + 1).name(), orders.get(i + 1))
                    .isLessThanOrEqualTo(orders.get(i + 1));
        }

        // 验证 Audit 排在最前（order=50）
        assertThat(middlewares.getFirst().name()).isEqualTo("audit");
    }

    @Test
    void Pipeline直接执行_快速路径命令() {
        var message = buildCliMessage(new MessageContent.CommandMessage("habit", List.of(), "/habit"));
        var response = pipeline.execute(message);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.tokenUsage()).isEqualTo(TokenUsage.ZERO);
    }

    // ── 网关状态测试 ──────────────────────────────────────────────

    @Test
    void 网关未启动时_返回503() {
        // 创建新网关实例，不调用 start()
        var freshGateway = new DefaultMessageGateway(pipeline);
        var message = buildCliMessage(new MessageContent.TextMessage("/todo"));
        var response = freshGateway.process(message);

        assertThat(response.statusCode()).isEqualTo(503);
    }

    // ── 辅助方法 ──────────────────────────────────────────────────

    private static GatewayMessage buildCliMessage(MessageContent content) {
        return GatewayMessage.builder()
                .channelType(ChannelType.CLI)
                .userId("test-user")
                .sessionId("test-session")
                .content(content)
                .build();
    }
}
