package com.lifepilot.meta.infra.interaction;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交互控制工具单元测试 — Mock InteractionBridge，验证 3 个交互工具。
 *
 * @author zsg
 * @since 2026-03-08
 */
class InteractionToolExecutorTest {

    private MetaProperties properties;
    private InteractionBridge bridge;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        // 设置较短超时便于测试
        properties.getInfra().getInteraction().setResponseTimeoutSeconds(2);
        // 使用 null channel — 测试中通过 resolve() 手动完成
        bridge = new InteractionBridge(properties, null, null);
    }

    // ─────────────────────────────────────────────
    //  InteractionBridge 核心测试
    // ─────────────────────────────────────────────

    @Test
    void bridge_无可用通道时_返回超时响应() {
        var request = new InteractionRequest(null, InteractionType.CONFIRM, "session-1", "确认？", null);
        var response = bridge.request(request);

        assertThat(response.timedOut()).isTrue();
    }

    @Test
    void bridge_resolve完成Future() {
        // 使用带 CLI handler 的 bridge
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);

        // 异步发起请求
        var futureResponse = CompletableFuture.supplyAsync(() -> {
            var request = new InteractionRequest(null, InteractionType.CONFIRM, "session-1", "确认？", null);
            return bridgeWithCli.request(request);
        });

        // 等待 CLI handler 收到请求
        waitForPending(cliHandler);

        // 通过 resolve 完成
        var capturedRequest = cliHandler.lastRequest;
        assertThat(capturedRequest).isNotNull();
        bridgeWithCli.resolve(capturedRequest.interactionId(),
                new InteractionResponse(capturedRequest.interactionId(), null, true, false));

        var response = futureResponse.join();
        assertThat(response.confirmed()).isTrue();
        assertThat(response.timedOut()).isFalse();
    }

    @Test
    void bridge_超时后自动清理pendingRequests() {
        var cliHandler = new TestCliInteractionHandler();
        var shortTimeoutProps = new MetaProperties();
        shortTimeoutProps.getInfra().getInteraction().setResponseTimeoutSeconds(1);
        var bridgeWithCli = new InteractionBridge(shortTimeoutProps, null, cliHandler);

        var request = new InteractionRequest(null, InteractionType.INPUT, "session-1", "输入", null);
        var response = bridgeWithCli.request(request);

        assertThat(response.timedOut()).isTrue();
        assertThat(bridgeWithCli.pendingCount()).isZero();
    }

    @Test
    void bridge_resolve不存在的interactionId_不抛异常() {
        bridge.resolve("non-existent-id",
                new InteractionResponse("non-existent-id", null, false, false));
        // 不抛异常即通过
    }

    @Test
    void bridge_notify非阻塞_无通道时不抛异常() {
        var request = new InteractionRequest(null, InteractionType.NOTIFY, "session-1", "通知消息", null);
        bridge.notify(request);
        // 不抛异常即通过
    }

    @Test
    void bridge_notify通过CLI推送() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);

        var request = new InteractionRequest(null, InteractionType.NOTIFY, "session-1", "通知消息", null);
        bridgeWithCli.notify(request);

        assertThat(cliHandler.lastRequest).isNotNull();
        assertThat(cliHandler.lastRequest.type()).isEqualTo(InteractionType.NOTIFY);
        assertThat(cliHandler.lastRequest.message()).isEqualTo("通知消息");
    }

    // ─────────────────────────────────────────────
    //  ChooseToolExecutor 测试
    // ─────────────────────────────────────────────

    @Test
    void choose_用户选择_返回选中值() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);
        var executor = new ChooseToolExecutor(bridgeWithCli);

        var futureResult = CompletableFuture.supplyAsync(() -> {
            ToolInput input = new ToolInput("builtin.interact.choose",
                    Map.of("message", "选择语言", "options", List.of("Java", "Python", "Go"), "sessionId", "s1"),
                    JsonSchema.empty(), null);
            return executor.execute(input);
        });

        waitForPending(cliHandler);
        var capturedRequest = cliHandler.lastRequest;
        assertThat(capturedRequest.options()).containsExactly("Java", "Python", "Go");
        bridgeWithCli.resolve(capturedRequest.interactionId(),
                new InteractionResponse(capturedRequest.interactionId(), "Python", false, false));

        ToolResult result = futureResult.join();
        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("selected")).isEqualTo("Python");
    }

    @Test
    void choose_空选项列表_返回错误() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);
        var executor = new ChooseToolExecutor(bridgeWithCli);

        ToolInput input = new ToolInput("builtin.interact.choose",
                Map.of("message", "选择", "options", List.of(), "sessionId", "s1"),
                JsonSchema.empty(), null);
        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("选项列表不能为空");
    }

    // ─────────────────────────────────────────────
    //  InputToolExecutor 测试
    // ─────────────────────────────────────────────

    @Test
    void input_用户输入_返回输入值() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);
        var executor = new InputToolExecutor(bridgeWithCli);

        var futureResult = CompletableFuture.supplyAsync(() -> {
            ToolInput input = new ToolInput("builtin.interact.input",
                    Map.of("message", "请输入姓名", "sessionId", "s1"),
                    JsonSchema.empty(), null);
            return executor.execute(input);
        });

        waitForPending(cliHandler);
        var capturedRequest = cliHandler.lastRequest;
        bridgeWithCli.resolve(capturedRequest.interactionId(),
                new InteractionResponse(capturedRequest.interactionId(), "张三", false, false));

        ToolResult result = futureResult.join();
        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("input")).isEqualTo("张三");
    }

    @Test
    void input_超时_返回错误() {
        var cliHandler = new TestCliInteractionHandler();
        var shortTimeoutProps = new MetaProperties();
        shortTimeoutProps.getInfra().getInteraction().setResponseTimeoutSeconds(1);
        var bridgeWithCli = new InteractionBridge(shortTimeoutProps, null, cliHandler);
        var executor = new InputToolExecutor(bridgeWithCli);

        ToolInput input = new ToolInput("builtin.interact.input",
                Map.of("message", "输入", "sessionId", "s1"),
                JsonSchema.empty(), null);
        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("超时");
    }

    // ─────────────────────────────────────────────
    //  NotifyToolExecutor 测试
    // ─────────────────────────────────────────────

    @Test
    void notify_非阻塞推送_立即返回() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);
        var executor = new NotifyToolExecutor(bridgeWithCli);

        ToolInput input = new ToolInput("builtin.interact.notify",
                Map.of("message", "任务已完成", "sessionId", "s1"),
                JsonSchema.empty(), null);

        // notify 应立即返回，不阻塞
        long start = System.currentTimeMillis();
        ToolResult result = executor.execute(input);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("notified")).isEqualTo(true);
        // 非阻塞验证：应在 1 秒内完成
        assertThat(elapsed).isLessThan(1000);
    }

    @Test
    void notify_缺少参数_返回错误() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);
        var executor = new NotifyToolExecutor(bridgeWithCli);

        ToolInput input = new ToolInput("builtin.interact.notify",
                Map.of(), JsonSchema.empty(), null);
        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("参数错误");
    }

    // ─────────────────────────────────────────────
    //  InteractionResponse 工厂方法测试
    // ─────────────────────────────────────────────

    @Test
    void interactionResponse_timeout工厂方法() {
        var response = InteractionResponse.timeout("test-id");

        assertThat(response.interactionId()).isEqualTo("test-id");
        assertThat(response.timedOut()).isTrue();
        assertThat(response.confirmed()).isFalse();
        assertThat(response.value()).isNull();
    }

    // ─────────────────────────────────────────────
    //  辅助类和方法
    // ─────────────────────────────────────────────

    /** 测试用 CLI 交互处理器 — 捕获最后一次推送的请求。 */
    private static class TestCliInteractionHandler implements CliInteractionHandler {
        volatile InteractionRequest lastRequest;

        @Override
        public void pushInteraction(InteractionRequest request) {
            this.lastRequest = request;
        }
    }

    /** 等待 CLI handler 收到请求（最多 2 秒）。 */
    private void waitForPending(TestCliInteractionHandler handler) {
        long deadline = System.currentTimeMillis() + 2000;
        while (handler.lastRequest == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
