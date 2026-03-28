package com.lifepilot.meta.infra.interaction;

import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.interaction.model.ChannelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交互工具执行器单元测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
class InteractionToolExecutorTest {

    private MetaProperties properties;
    private InteractionBridge bridge;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        properties.getInfra().getInteraction().setResponseTimeoutSeconds(2);
        bridge = new InteractionBridge(properties, null, null);
    }

    @Test
    void bridge_无可用通道时返回超时响应() {
        var request = new InteractionRequest(null, InteractionType.CONFIRM, "session-1", null, "确认吗", null);

        var response = bridge.request(request);

        assertThat(response.timedOut()).isTrue();
    }

    @Test
    void bridge_resolve后完成等待中的请求() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);

        var futureResponse = CompletableFuture.supplyAsync(() ->
                bridgeWithCli.request(new InteractionRequest(
                        null, InteractionType.CONFIRM, "session-1", null, "确认吗", null)));

        waitForPending(cliHandler);
        var capturedRequest = cliHandler.lastRequest;

        assertThat(capturedRequest).isNotNull();
        bridgeWithCli.resolve(
                capturedRequest.interactionId(),
                new InteractionResponse(capturedRequest.interactionId(), null, true, false));

        var response = futureResponse.join();
        assertThat(response.confirmed()).isTrue();
        assertThat(response.timedOut()).isFalse();
    }

    @Test
    void bridge_超时后自动清理挂起请求() {
        var cliHandler = new TestCliInteractionHandler();
        var shortTimeoutProps = new MetaProperties();
        shortTimeoutProps.getInfra().getInteraction().setResponseTimeoutSeconds(1);
        var bridgeWithCli = new InteractionBridge(shortTimeoutProps, null, cliHandler);

        var response = bridgeWithCli.request(new InteractionRequest(
                null, InteractionType.INPUT, "session-1", null, "请输入", null));

        assertThat(response.timedOut()).isTrue();
        assertThat(bridgeWithCli.pendingCount()).isZero();
    }

    @Test
    void bridge_缺少精确流时按会话活动流兜底推送() {
        var sseSessionManager = mock(SseSessionManager.class);
        when(sseSessionManager.getEmitter("missing-stream")).thenReturn(null);
        when(sseSessionManager.findChatStreamId("session-1")).thenReturn("stream-1");
        when(sseSessionManager.getEmitter("stream-1")).thenReturn(mock(SseEmitter.class));

        var pushedRequest = new AtomicReference<InteractionRequest>();
        doAnswer(invocation -> {
            pushedRequest.set(invocation.getArgument(2));
            return null;
        }).when(sseSessionManager).sendEvent(eq("stream-1"), eq(SseEventType.INTERACTION), any());

        var bridgeWithSse = new InteractionBridge(properties, sseSessionManager, null);
        var futureResponse = CompletableFuture.supplyAsync(() ->
                bridgeWithSse.request(new InteractionRequest(
                        null, InteractionType.INPUT, "session-1", "missing-stream", "请输入仓库地址", null)));

        waitFor(() -> pushedRequest.get() != null);
        var routedRequest = pushedRequest.get();
        assertThat(routedRequest).isNotNull();
        assertThat(routedRequest.streamId()).isEqualTo("stream-1");
        assertThat(routedRequest.sessionId()).isEqualTo("session-1");

        bridgeWithSse.resolve(
                routedRequest.interactionId(),
                new InteractionResponse(routedRequest.interactionId(), "https://github.com/acme/demo.git", false, false));

        var response = futureResponse.join();
        assertThat(response.value()).isEqualTo("https://github.com/acme/demo.git");
        verify(sseSessionManager).findChatStreamId("session-1");
    }

    @Test
    void choose_使用上下文中的会话和流信息() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);
        var executor = new ChooseToolExecutor(bridgeWithCli);

        var futureResult = CompletableFuture.supplyAsync(() -> executor.execute(new ToolInput(
                "interact.choose",
                Map.of("message", "选择语言", "options", List.of("Java", "Python", "Go")),
                JsonSchema.empty(),
                null,
                Map.of(
                        ToolContextKeys.SESSION_ID, "session-1",
                        ToolContextKeys.STREAM_ID, "stream-1"
                )
        )));

        waitForPending(cliHandler);
        var capturedRequest = cliHandler.lastRequest;
        assertThat(capturedRequest).isNotNull();
        assertThat(capturedRequest.sessionId()).isEqualTo("session-1");
        assertThat(capturedRequest.streamId()).isEqualTo("stream-1");
        assertThat(capturedRequest.options()).containsExactly("Java", "Python", "Go");

        bridgeWithCli.resolve(
                capturedRequest.interactionId(),
                new InteractionResponse(capturedRequest.interactionId(), "Python", false, false));

        ToolResult result = futureResult.join();
        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("selected")).isEqualTo("Python");
    }

    @Test
    void choose_空选项列表返回错误() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);
        var executor = new ChooseToolExecutor(bridgeWithCli);

        ToolResult result = executor.execute(new ToolInput(
                "interact.choose",
                Map.of("message", "选择", "options", List.of()),
                JsonSchema.empty(),
                null,
                Map.of(ToolContextKeys.SESSION_ID, "session-1")
        ));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("选项列表不能为空");
    }

    @Test
    void input_使用上下文中的会话和流信息() {
        var cliHandler = new TestCliInteractionHandler();
        var bridgeWithCli = new InteractionBridge(properties, null, cliHandler);
        var executor = new InputToolExecutor(bridgeWithCli);

        var futureResult = CompletableFuture.supplyAsync(() -> executor.execute(new ToolInput(
                "interact.input",
                Map.of("message", "请输入姓名"),
                JsonSchema.empty(),
                null,
                Map.of(
                        ToolContextKeys.SESSION_ID, "session-1",
                        ToolContextKeys.STREAM_ID, "stream-1"
                )
        )));

        waitForPending(cliHandler);
        var capturedRequest = cliHandler.lastRequest;
        assertThat(capturedRequest).isNotNull();
        assertThat(capturedRequest.sessionId()).isEqualTo("session-1");
        assertThat(capturedRequest.streamId()).isEqualTo("stream-1");

        bridgeWithCli.resolve(
                capturedRequest.interactionId(),
                new InteractionResponse(capturedRequest.interactionId(), "张三", false, false));

        ToolResult result = futureResult.join();
        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("input")).isEqualTo("张三");
    }

    @Test
    void input_超时返回错误() {
        var cliHandler = new TestCliInteractionHandler();
        var shortTimeoutProps = new MetaProperties();
        shortTimeoutProps.getInfra().getInteraction().setResponseTimeoutSeconds(1);
        var bridgeWithCli = new InteractionBridge(shortTimeoutProps, null, cliHandler);
        var executor = new InputToolExecutor(bridgeWithCli);

        ToolResult result = executor.execute(new ToolInput(
                "interact.input",
                Map.of("message", "请输入"),
                JsonSchema.empty(),
                null,
                Map.of(ToolContextKeys.SESSION_ID, "session-1")
        ));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("超时");
    }

    @Test
    void notify_通过通知服务发送消息() {
        var notificationService = new TestNotificationService();
        var executor = new NotifyToolExecutor(
                notificationService,
                new com.lifepilot.notification.config.NotificationProperties());

        ToolResult result = executor.execute(new ToolInput(
                "interact.notify",
                Map.of("message", "任务已完成"),
                JsonSchema.empty(),
                null,
                Map.of(
                        ToolContextKeys.SESSION_ID, "session-1",
                        ToolContextKeys.CHANNEL_TYPE, ChannelType.WEB.value(),
                        ToolContextKeys.USER_ID, "user-1"
                )
        ));

        assertThat(result.ok()).isTrue();
        assertThat(notificationService.sentRequests).hasSize(1);
        assertThat(notificationService.sentRequests.getFirst().content().toPlainText()).isEqualTo("任务已完成");
        assertThat(notificationService.sentRequests.getFirst().channel()).isEqualTo("WEB");
        assertThat(notificationService.sentRequests.getFirst().targetUserId()).isEqualTo("user-1");
    }

    @Test
    void interactionResponse_timeout工厂方法返回超时响应() {
        var response = InteractionResponse.timeout("test-id");

        assertThat(response.interactionId()).isEqualTo("test-id");
        assertThat(response.timedOut()).isTrue();
        assertThat(response.confirmed()).isFalse();
        assertThat(response.value()).isNull();
    }

    private static class TestCliInteractionHandler implements CliInteractionHandler {
        volatile InteractionRequest lastRequest;

        @Override
        public void pushInteraction(InteractionRequest request) {
            this.lastRequest = request;
        }
    }

    private static class TestNotificationService implements NotificationService {
        final List<NotificationRequest> sentRequests = new ArrayList<>();

        @Override
        public List<String> send(NotificationRequest request) {
            sentRequests.add(request);
            return List.of("test-notification-id");
        }
    }

    private void waitForPending(TestCliInteractionHandler handler) {
        waitFor(() -> handler.lastRequest != null);
    }

    private void waitFor(Check condition) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.ready() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.ready()).isTrue();
    }

    @FunctionalInterface
    private interface Check {
        boolean ready();
    }
}
