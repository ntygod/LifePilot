package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.UserConfirmationService;
import com.lifepilot.interaction.web.model.ConfirmationRequest;
import com.lifepilot.interaction.web.repository.ChatMessageRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Web 端用户确认服务实现。
 *
 * <p>通过 SSE 推送确认请求到前端，使用 {@link CompletableFuture} 阻塞等待用户响应。
 * HIGH/CRITICAL 风险工具执行前由 {@link com.lifepilot.tool.pipeline.ToolExecutionPipeline}
 * 调用此服务请求用户确认。</p>
 *
 * <p>确认结果会持久化到 chat_messages 表（role = tool-confirmation），
 * 刷新页面后仍可在对话流中看到历史确认记录。</p>
 *
 * @author zsg
 * @since 2026-03-11
 */
public class WebUserConfirmationService implements UserConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(WebUserConfirmationService.class);

    private final SseSessionManager sseSessionManager;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;
    private final long confirmationTimeoutSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingConfirmations
            = new ConcurrentHashMap<>();
    /** 暂存每个 requestId 对应的元数据，用于在 resolve/timeout 时持久化 */
    private final ConcurrentHashMap<String, PendingMeta> pendingMeta
            = new ConcurrentHashMap<>();

    /** 确认请求的暂存元数据。 */
    private record PendingMeta(
            String sessionId,
            String toolId,
            String toolName,
            String riskLevel,
            String message,
            Instant createdAt
    ) {}

    public WebUserConfirmationService(SseSessionManager sseSessionManager,
                                       ChatMessageRepository chatMessageRepository,
                                       ObjectMapper objectMapper,
                                       long confirmationTimeoutSeconds) {
        this.sseSessionManager = sseSessionManager;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
        this.confirmationTimeoutSeconds = confirmationTimeoutSeconds;
    }

    @Override
    public boolean requestConfirmation(ToolContract tool, ToolInput input, String message,
                                       @Nullable String streamId) {
        String requestId = UUID.randomUUID().toString();
        var future = new CompletableFuture<Boolean>();
        pendingConfirmations.put(requestId, future);

        // 从 ToolInput context 中提取 sessionId
        String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
        var now = Instant.now();

        // 暂存元数据，resolve/timeout 时用于持久化
        pendingMeta.put(requestId, new PendingMeta(
                sessionId, tool.id(), tool.name(),
                tool.riskLevel().name(), message, now));

        // 构建确认请求
        var request = new ConfirmationRequest(
                requestId, tool.id(), tool.name(),
                tool.riskLevel().name(), tool.riskLevel().toApprovalMode().name(),
                message, streamId, now.toString());

        // 通过 SSE 精确推送到发起请求的聊天流
        if (streamId != null) {
            sseSessionManager.sendEvent(streamId,
                    SseEventType.TOOL_CONFIRMATION_REQUEST, request);
            log.info("工具确认请求已精确推送: requestId={}, toolId={}, streamId={}",
                    requestId, tool.id(), streamId);
        } else {
            log.warn("工具确认请求无 streamId，跳过 SSE 推送: requestId={}, toolId={}",
                    requestId, tool.id());
        }

        try {
            boolean confirmed = future.get(confirmationTimeoutSeconds, TimeUnit.SECONDS);
            persistConfirmation(requestId, confirmed ? "approved" : "rejected");
            return confirmed;
        } catch (TimeoutException e) {
            log.info("工具确认超时: requestId={}, toolId={}", requestId, tool.id());
            persistConfirmation(requestId, "expired");
            return false;
        } catch (Exception e) {
            log.error("工具确认异常: requestId={}", requestId, e);
            persistConfirmation(requestId, "expired");
            return false;
        } finally {
            pendingConfirmations.remove(requestId);
            pendingMeta.remove(requestId);
        }
    }

    /**
     * 解除阻塞等待，传递用户确认结果。
     *
     * @param requestId 确认请求 ID
     * @param confirmed 用户是否确认
     * @return 是否成功解除（false 表示 requestId 不存在或已过期）
     */
    public boolean resolveConfirmation(String requestId, boolean confirmed) {
        var future = pendingConfirmations.remove(requestId);
        if (future == null) {
            log.debug("确认请求不存在或已过期: requestId={}", requestId);
            return false;
        }
        future.complete(confirmed);
        log.info("工具确认已解除: requestId={}, confirmed={}", requestId, confirmed);
        return true;
    }

    /**
     * 将确认结果持久化到 chat_messages 表。
     *
     * <p>content 字段存储 JSON 格式的确认详情，前端加载历史消息时解析还原。</p>
     */
    private void persistConfirmation(String requestId, String resolution) {
        var meta = pendingMeta.get(requestId);
        if (meta == null || meta.sessionId() == null) {
            log.debug("跳过确认持久化: requestId={}, 无 sessionId", requestId);
            return;
        }
        try {
            String contentJson = objectMapper.writeValueAsString(Map.of(
                    "requestId", requestId,
                    "toolId", meta.toolId(),
                    "toolName", meta.toolName(),
                    "riskLevel", meta.riskLevel(),
                    "message", meta.message() != null ? meta.message() : "",
                    "resolution", resolution
            ));
            chatMessageRepository.insert(
                    meta.sessionId(),
                    "tool-confirmation",
                    contentJson,
                    null, null,
                    meta.createdAt(),
                    null, null);
            log.info("工具确认已持久化: requestId={}, sessionId={}, resolution={}",
                    requestId, meta.sessionId(), resolution);
        } catch (Exception e) {
            log.warn("工具确认持久化失败: requestId={}, error={}", requestId, e.getMessage());
        }
    }
}
