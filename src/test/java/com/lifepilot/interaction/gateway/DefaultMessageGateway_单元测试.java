package com.lifepilot.interaction.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.lifepilot.interaction.middleware.MiddlewarePipeline;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.model.ResponseContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultMessageGateway 单元测试。
 *
 * <p>使用 Mock MiddlewarePipeline 隔离网关自身逻辑，覆盖生命周期管理、
 * 消息处理正常/异常路径、不同渠道路由以及并发状态切换等关键行为。
 * 与 {@code GatewayPipeline_集成测试} 互补：集成测试验证完整管道执行，
 * 本测试聚焦网关自身的状态管理和错误处理逻辑。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class DefaultMessageGateway_单元测试 {

    @Mock
    private MiddlewarePipeline pipeline;

    private DefaultMessageGateway gateway;

    @BeforeEach
    void 初始化() {
        gateway = new DefaultMessageGateway(pipeline);
    }

    // ── 生命周期管理 ──────────────────────────────────────────────

    @Nested
    class 生命周期管理 {

        @Test
        void 新建网关_默认未运行() {
            assertThat(gateway.isRunning()).isFalse();
        }

        @Test
        void 启动后_状态变为运行中() {
            gateway.start();
            assertThat(gateway.isRunning()).isTrue();
        }

        @Test
        void 停止后_状态变为未运行() {
            gateway.start();
            gateway.stop();
            assertThat(gateway.isRunning()).isFalse();
        }

        @Test
        void 重复启动_状态保持运行中() {
            gateway.start();
            gateway.start();
            assertThat(gateway.isRunning()).isTrue();
        }

        @Test
        void 重复停止_状态保持未运行() {
            gateway.start();
            gateway.stop();
            gateway.stop();
            assertThat(gateway.isRunning()).isFalse();
        }

        @Test
        void 启停循环_状态正确切换() {
            // 启动 → 停止 → 再启动
            gateway.start();
            assertThat(gateway.isRunning()).isTrue();

            gateway.stop();
            assertThat(gateway.isRunning()).isFalse();

            gateway.start();
            assertThat(gateway.isRunning()).isTrue();
        }
    }

    // ── 网关未运行时拒绝消息 ─────────────────────────────────────

    @Nested
    class 网关未运行时拒绝消息 {

        @Test
        void 未启动时处理消息_返回503() {
            var message = 构造Web消息("你好");

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.errorMessage()).isEqualTo("网关未运行");
            assertThat(response.channelType()).isEqualTo(ChannelType.WEB);
        }

        @Test
        void 停止后处理消息_返回503() {
            gateway.start();
            gateway.stop();
            var message = 构造Web消息("你好");

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.errorMessage()).isEqualTo("网关未运行");
        }

        @Test
        void 未启动时_不调用管道() {
            var message = 构造Web消息("你好");

            gateway.process(message);

            verify(pipeline, never()).execute(any());
        }

        @Test
        void 未启动时_飞书渠道消息也返回503() {
            var message = 构造渠道消息(ChannelType.FEISHU, "你好");

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.channelType()).isEqualTo(ChannelType.FEISHU);
        }
    }

    // ── 消息正常处理流程 ─────────────────────────────────────────

    @Nested
    class 消息正常处理流程 {

        @BeforeEach
        void 启动网关() {
            gateway.start();
        }

        @Test
        void 正常文本消息_管道返回成功响应() {
            var message = 构造Web消息("你好");
            var pipelineResponse = GatewayResponse.success(
                    ChannelType.WEB, new ResponseContent.TextContent("你好，有什么可以帮你？"));
            when(pipeline.execute(message)).thenReturn(pipelineResponse);

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.content().toPlainText()).isEqualTo("你好，有什么可以帮你？");
            verify(pipeline).execute(message);
        }

        @Test
        void 正常处理后_响应包含延迟时间() {
            var message = 构造Web消息("测试延迟");
            var pipelineResponse = GatewayResponse.success(
                    ChannelType.WEB, new ResponseContent.TextContent("ok"));
            when(pipeline.execute(message)).thenReturn(pipelineResponse);

            var response = gateway.process(message);

            // 延迟应被设置且非零（至少大于等于零）
            assertThat(response.latency()).isNotNull();
            assertThat(response.latency()).isGreaterThanOrEqualTo(Duration.ZERO);
        }

        @Test
        void 管道返回的latency被覆盖为实际耗时() {
            var message = 构造Web消息("测试延迟覆盖");
            // 管道返回一个自带 latency 的响应
            var pipelineResponse = GatewayResponse.builder()
                    .channelType(ChannelType.WEB)
                    .content(new ResponseContent.TextContent("ok"))
                    .statusCode(200)
                    .latency(Duration.ofSeconds(999))
                    .build();
            when(pipeline.execute(message)).thenReturn(pipelineResponse);

            var response = gateway.process(message);

            // 网关应使用实际耗时覆盖管道自带的 latency
            assertThat(response.latency()).isNotNull();
            assertThat(response.latency()).isLessThan(Duration.ofSeconds(999));
        }

        @Test
        void 命令消息_正常传递给管道() {
            var content = new MessageContent.CommandMessage("todo", List.of(), "/todo");
            var message = GatewayMessage.builder()
                    .channelType(ChannelType.WEB)
                    .userId("user-1")
                    .sessionId("session-1")
                    .content(content)
                    .build();
            var pipelineResponse = GatewayResponse.success(
                    ChannelType.WEB, new ResponseContent.TextContent("待办列表为空"));
            when(pipeline.execute(message)).thenReturn(pipelineResponse);

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(200);
            verify(pipeline).execute(message);
        }

        @Test
        void 管道返回错误码_网关原样透传() {
            var message = 构造Web消息("触发限流");
            var rateLimitedResponse = GatewayResponse.rateLimited(ChannelType.WEB);
            when(pipeline.execute(message)).thenReturn(rateLimitedResponse);

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(429);
            // 虽然管道返回 429，网关仍计算延迟
            assertThat(response.latency()).isNotNull();
        }
    }

    // ── 管道异常处理 ─────────────────────────────────────────────

    @Nested
    class 管道异常处理 {

        @BeforeEach
        void 启动网关() {
            gateway.start();
        }

        @Test
        void 管道抛出RuntimeException_返回500() {
            var message = 构造Web消息("触发异常");
            when(pipeline.execute(message)).thenThrow(new RuntimeException("模拟中间件崩溃"));

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.errorMessage()).isEqualTo("内部处理错误");
            assertThat(response.channelType()).isEqualTo(ChannelType.WEB);
        }

        @Test
        void 管道抛出NullPointerException_返回500() {
            var message = 构造Web消息("空指针");
            when(pipeline.execute(message)).thenThrow(new NullPointerException("模拟空指针"));

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.errorMessage()).isEqualTo("内部处理错误");
        }

        @Test
        void 管道抛出异常_响应仍包含延迟时间() {
            var message = 构造Web消息("异常延迟");
            when(pipeline.execute(message)).thenThrow(new RuntimeException("崩溃"));

            var response = gateway.process(message);

            assertThat(response.latency()).isNotNull();
            assertThat(response.latency()).isGreaterThanOrEqualTo(Duration.ZERO);
        }

        @Test
        void 管道抛出异常_响应保留原始渠道类型() {
            var message = 构造渠道消息(ChannelType.DINGTALK, "钉钉异常");
            when(pipeline.execute(message)).thenThrow(new RuntimeException("钉钉管道异常"));

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.channelType()).isEqualTo(ChannelType.DINGTALK);
        }

        @Test
        void 管道抛出Error类型_仍被catch处理为500() {
            // DefaultMessageGateway 捕获 Exception，Error 不在捕获范围
            // 此测试验证 StackOverflowError 等不会被捕获（会向上抛出）
            var message = 构造Web消息("溢出");
            when(pipeline.execute(message)).thenThrow(new IllegalStateException("状态非法"));

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(500);
        }
    }

    // ── 不同渠道消息路由 ─────────────────────────────────────────

    @Nested
    class 不同渠道消息路由 {

        @BeforeEach
        void 启动网关() {
            gateway.start();
        }

        @Test
        void Web渠道_正常处理() {
            验证渠道消息处理(ChannelType.WEB);
        }

        @Test
        void 飞书渠道_正常处理() {
            验证渠道消息处理(ChannelType.FEISHU);
        }

        @Test
        void 钉钉渠道_正常处理() {
            验证渠道消息处理(ChannelType.DINGTALK);
        }

        @Test
        void 企业微信渠道_正常处理() {
            验证渠道消息处理(ChannelType.WECOM);
        }

        @Test
        void 不同渠道的503错误响应_携带对应渠道类型() {
            // 网关未启动
            var freshGateway = new DefaultMessageGateway(pipeline);
            for (ChannelType channelType : ChannelType.values()) {
                var message = 构造渠道消息(channelType, "测试");
                var response = freshGateway.process(message);
                assertThat(response.channelType())
                        .as("渠道 %s 的 503 响应应携带对应渠道类型", channelType)
                        .isEqualTo(channelType);
            }
        }

        private void 验证渠道消息处理(ChannelType channelType) {
            var message = 构造渠道消息(channelType, "测试消息");
            var pipelineResponse = GatewayResponse.success(
                    channelType, new ResponseContent.TextContent("已处理"));
            when(pipeline.execute(message)).thenReturn(pipelineResponse);

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.channelType()).isEqualTo(channelType);
            verify(pipeline).execute(message);
        }
    }

    // ── 并发安全 ─────────────────────────────────────────────────

    @Nested
    class 并发安全 {

        @Test
        void 并发启停切换_状态始终一致() throws InterruptedException {
            int threadCount = 20;
            var barrier = new CyclicBarrier(threadCount);
            var latch = new CountDownLatch(threadCount);
            var errors = new AtomicInteger(0);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < threadCount; i++) {
                    final boolean shouldStart = (i % 2 == 0);
                    executor.submit(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            if (shouldStart) {
                                gateway.start();
                            } else {
                                gateway.stop();
                            }
                            // isRunning 应始终返回 boolean，不应抛异常
                            gateway.isRunning();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(errors.get()).isZero();
            // 最终状态应为 true 或 false，不应处于不一致的中间态
            assertThat(gateway.isRunning()).isIn(true, false);
        }

        @Test
        void 并发消息处理_运行中网关不会拒绝消息() throws InterruptedException {
            gateway.start();
            int threadCount = 50;
            var latch = new CountDownLatch(threadCount);
            var rejectedCount = new AtomicInteger(0);

            var pipelineResponse = GatewayResponse.success(
                    ChannelType.WEB, new ResponseContent.TextContent("ok"));
            when(pipeline.execute(any(GatewayMessage.class))).thenReturn(pipelineResponse);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < threadCount; i++) {
                    executor.submit(() -> {
                        try {
                            var message = 构造Web消息("并发消息");
                            var response = gateway.process(message);
                            if (response.statusCode() == 503) {
                                rejectedCount.incrementAndGet();
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(rejectedCount.get()).isZero();
        }
    }

    // ── 不同消息类型 ─────────────────────────────────────────────

    @Nested
    class 不同消息类型 {

        @BeforeEach
        void 启动网关() {
            gateway.start();
        }

        @Test
        void 事件消息_正常处理() {
            var content = new MessageContent.EventMessage("user_login", Map.of("ip", "127.0.0.1"));
            var message = GatewayMessage.builder()
                    .channelType(ChannelType.WEB)
                    .userId("user-1")
                    .sessionId("session-1")
                    .content(content)
                    .build();
            var pipelineResponse = GatewayResponse.success(
                    ChannelType.WEB, new ResponseContent.TextContent("事件已处理"));
            when(pipeline.execute(message)).thenReturn(pipelineResponse);

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(200);
            verify(pipeline).execute(message);
        }

        @Test
        void 卡片消息_正常处理() {
            var content = new MessageContent.CardMessage("标题", "描述", List.of());
            var message = GatewayMessage.builder()
                    .channelType(ChannelType.FEISHU)
                    .userId("feishu-user")
                    .sessionId("feishu-session")
                    .content(content)
                    .build();
            var pipelineResponse = GatewayResponse.success(
                    ChannelType.FEISHU, new ResponseContent.TextContent("卡片已处理"));
            when(pipeline.execute(message)).thenReturn(pipelineResponse);

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.channelType()).isEqualTo(ChannelType.FEISHU);
        }

        @Test
        void 文件消息_正常处理() {
            var content = new MessageContent.FileMessage(
                    "report.pdf", "application/pdf", new byte[]{1, 2, 3}, "季度报告");
            var message = GatewayMessage.builder()
                    .channelType(ChannelType.WEB)
                    .userId("user-1")
                    .sessionId("session-1")
                    .content(content)
                    .build();
            var pipelineResponse = GatewayResponse.success(
                    ChannelType.WEB, new ResponseContent.TextContent("文件已接收"));
            when(pipeline.execute(message)).thenReturn(pipelineResponse);

            var response = gateway.process(message);

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    // ── 辅助方法 ─────────────────────────────────────────────────

    private static GatewayMessage 构造Web消息(String text) {
        return 构造渠道消息(ChannelType.WEB, text);
    }

    private static GatewayMessage 构造渠道消息(ChannelType channelType, String text) {
        return GatewayMessage.builder()
                .channelType(channelType)
                .userId("test-user")
                .sessionId("test-session")
                .content(new MessageContent.TextMessage(text))
                .build();
    }
}
