package com.lifepilot.a2a.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.A2aJsonRpcError;
import com.lifepilot.a2a.model.A2aMessage;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.mcp.protocol.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * A2A JSON-RPC 2.0 端点。
 *
 * <p>单一入口 {@code POST /api/a2a}，通过 JSON-RPC method 字段路由：
 * <ul>
 *   <li>{@code tasks/send} — 同步执行消息，返回 {@code application/json} JSON-RPC response</li>
 *   <li>{@code tasks/sendSubscribe} — SSE 流式执行。<b>成功时返回 {@code text/event-stream}
 *       （SseEmitter），失败时返回 {@code application/json} JSON-RPC error response。</b>
 *       客户端应根据 Content-Type 区分响应类型。</li>
 *   <li>{@code tasks/get} — 查询 Task 状态</li>
 *   <li>{@code tasks/cancel} — 取消 Task</li>
 * </ul>
 *
 * <p>所有 JSON-RPC error 响应均使用 HTTP 200，错误语义由 JSON body 中的 error 对象承载。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
@RestController
@RequestMapping("/api/a2a")
@ConditionalOnProperty(prefix = "lifepilot.a2a.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class A2aJsonRpcController {

    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcController.class);

    private final A2aAgentExecutor executor;
    private final A2aTaskStore taskStore;
    private final A2aProperties properties;
    private final ObjectMapper objectMapper;

    public A2aJsonRpcController(A2aAgentExecutor executor,
                                 A2aTaskStore taskStore,
                                 A2aProperties properties,
                                 ObjectMapper objectMapper) {
        this.executor = executor;
        this.taskStore = taskStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * JSON-RPC 2.0 统一入口。
     */
    @PostMapping(value = "", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handleJsonRpc(@RequestBody JsonRpcMessage request) {
        if (!"2.0".equals(request.jsonrpc())) {
            return errorResponse(request, A2aJsonRpcError.invalidRequest("jsonrpc 版本必须为 2.0"));
        }
        if (request.method() == null || request.method().isBlank()) {
            return errorResponse(request, A2aJsonRpcError.invalidRequest("method 不能为空"));
        }

        log.debug("收到 A2A JSON-RPC 请求: method={}, id={}", request.method(), request.id());

        return switch (request.method()) {
            case "tasks/send" -> handleTasksSend(request);
            case "tasks/sendSubscribe" -> handleTasksSendSubscribe(request);
            case "tasks/get" -> handleTasksGet(request);
            case "tasks/cancel" -> handleTasksCancel(request);
            default -> errorResponse(request, A2aJsonRpcError.methodNotFound(request.method()));
        };
    }

    /** tasks/send — 同步执行。 */
    private ResponseEntity<?> handleTasksSend(JsonRpcMessage request) {
        var parsed = parseMessageParams(request);
        if (parsed == null) {
            return errorResponse(request, A2aJsonRpcError.invalidParams("params 必须包含 message 字段"));
        }

        var validationError = A2aMessageValidator.validate(parsed.message());
        if (validationError != null) {
            return errorResponse(request, A2aJsonRpcError.invalidParams(validationError));
        }

        try {
            var task = executor.execute(parsed.message(), parsed.skillId());
            return ResponseEntity.ok(jsonRpcResponse(request.id(), task));
        } catch (Exception e) {
            log.error("tasks/send 执行异常: id={}", request.id(), e);
            return errorResponse(request, A2aJsonRpcError.internalError(e.getMessage()));
        }
    }

    /**
     * tasks/sendSubscribe — SSE 流式执行。
     *
     * <p>成功时返回 {@code text/event-stream}（SseEmitter），每个 SSE 事件以
     * JSON-RPC notification 格式推送。校验失败或流式禁用时返回
     * {@code application/json} JSON-RPC error response。</p>
     */
    private ResponseEntity<?> handleTasksSendSubscribe(JsonRpcMessage request) {
        if (!properties.getServer().isStreamingEnabled()) {
            return errorResponse(request,
                    new A2aJsonRpcError(A2aJsonRpcError.STREAMING_DISABLED, "流式消息处理已禁用", null));
        }

        var parsed = parseMessageParams(request);
        if (parsed == null) {
            return errorResponse(request, A2aJsonRpcError.invalidParams("params 必须包含 message 字段"));
        }

        var validationError = A2aMessageValidator.validate(parsed.message());
        if (validationError != null) {
            return errorResponse(request, A2aJsonRpcError.invalidParams(validationError));
        }

        long timeoutMs = properties.getServer().getSseTimeoutSeconds() * 1000L;
        var emitter = new SseEmitter(timeoutMs);

        executor.executeStreaming(parsed.message(), parsed.skillId(), task -> {
            try {
                String eventType = task.status().state().isTerminal()
                        ? SseEventType.TASK_COMPLETE
                        : (task.artifacts() != null && !task.artifacts().isEmpty())
                                ? SseEventType.TASK_ARTIFACT_UPDATE
                                : SseEventType.TASK_STATUS_UPDATE;

                var notification = JsonRpcMessage.notification(eventType, task);
                var event = SseEmitter.event()
                        .name(eventType)
                        .data(notification);
                emitter.send(event);

                if (task.status().state().isTerminal()) {
                    emitter.complete();
                }
            } catch (IOException e) {
                log.warn("SSE 发送事件失败: taskId={}", task.id(), e);
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> log.info("A2A JSON-RPC SSE 超时: id={}", request.id()));
        emitter.onError(ex -> log.warn("A2A JSON-RPC SSE 异常: id={}", request.id(), ex));

        return ResponseEntity.ok(emitter);
    }

    /** tasks/get — 查询 Task。 */
    private ResponseEntity<?> handleTasksGet(JsonRpcMessage request) {
        String taskId = extractTaskId(request);
        if (taskId == null) {
            return errorResponse(request, A2aJsonRpcError.invalidParams("params 必须包含 id 字段"));
        }

        return taskStore.find(taskId)
                .map(task -> ResponseEntity.ok(jsonRpcResponse(request.id(), task)))
                .orElseGet(() -> errorResponse(request,
                        new A2aJsonRpcError(A2aJsonRpcError.TASK_NOT_FOUND, "Task 不存在: " + taskId, null)));
    }

    /** tasks/cancel — 取消 Task。 */
    private ResponseEntity<?> handleTasksCancel(JsonRpcMessage request) {
        String taskId = extractTaskId(request);
        if (taskId == null) {
            return errorResponse(request, A2aJsonRpcError.invalidParams("params 必须包含 id 字段"));
        }

        if (taskStore.find(taskId).isEmpty()) {
            return errorResponse(request,
                    new A2aJsonRpcError(A2aJsonRpcError.TASK_NOT_FOUND, "Task 不存在: " + taskId, null));
        }

        boolean canceled = taskStore.cancel(taskId);
        if (!canceled) {
            return errorResponse(request,
                    new A2aJsonRpcError(A2aJsonRpcError.TASK_TERMINAL, "Task 已处于终态，无法取消", null));
        }

        return taskStore.find(taskId)
                .map(task -> ResponseEntity.ok(jsonRpcResponse(request.id(), task)))
                .orElseGet(() -> errorResponse(request,
                        A2aJsonRpcError.internalError("取消后 Task 丢失")));
    }

    // ── 辅助方法 ──

    /** 从 params 中解析 A2aMessage 和 skillId。 */
    private MessageParams parseMessageParams(JsonRpcMessage request) {
        if (!(request.params() instanceof Map<?, ?> params)) {
            return null;
        }
        Object msgObj = params.get("message");
        if (msgObj == null) {
            return null;
        }
        try {
            A2aMessage message = objectMapper.convertValue(msgObj, A2aMessage.class);
            String skillId = params.get("skillId") instanceof String s ? s : null;
            return new MessageParams(message, skillId);
        } catch (Exception e) {
            log.warn("JSON-RPC params 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 从 params 中提取 Task ID。 */
    private String extractTaskId(JsonRpcMessage request) {
        if (!(request.params() instanceof Map<?, ?> params)) {
            return null;
        }
        return params.get("id") instanceof String id ? id : null;
    }

    /** 构建 JSON-RPC 成功响应（保留原始 id，null 则传 null）。 */
    private JsonRpcMessage jsonRpcResponse(Long id, Object result) {
        long effectiveId = id != null ? id : 0;
        return JsonRpcMessage.response(effectiveId, result);
    }

    /** 构建 JSON-RPC 错误响应（统一 HTTP 200，错误语义由 error 对象承载）。 */
    private ResponseEntity<JsonRpcMessage> errorResponse(JsonRpcMessage request, A2aJsonRpcError error) {
        var response = new JsonRpcMessage("2.0", request.id(), null, null, null, error);
        return ResponseEntity.ok(response);
    }

    /** 消息参数解析结果。 */
    private record MessageParams(A2aMessage message, String skillId) {}
}
