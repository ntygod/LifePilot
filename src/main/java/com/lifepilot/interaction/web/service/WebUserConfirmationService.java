package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.UserConfirmationService;
import com.lifepilot.interaction.web.model.ConfirmationRequest;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
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
 * @author zsg
 * @since 2026-03-11
 */
public class WebUserConfirmationService implements UserConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(WebUserConfirmationService.class);

    private final SseSessionManager sseSessionManager;
    private final long confirmationTimeoutSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingConfirmations
            = new ConcurrentHashMap<>();

    public WebUserConfirmationService(SseSessionManager sseSessionManager,
                                       long confirmationTimeoutSeconds) {
        this.sseSessionManager = sseSessionManager;
        this.confirmationTimeoutSeconds = confirmationTimeoutSeconds;
    }

    @Override
    public boolean requestConfirmation(ToolContract tool, ToolInput input, String message) {
        String requestId = UUID.randomUUID().toString();
        var future = new CompletableFuture<Boolean>();
        pendingConfirmations.put(requestId, future);

        // 构建确认请求
        var request = new ConfirmationRequest(
                requestId, tool.id(), tool.name(),
                tool.riskLevel().name(), tool.riskLevel().toApprovalMode().name(),
                message, Instant.now().toString());

        // 通过 SSE 广播到所有活跃聊天流
        sseSessionManager.broadcastByPrefix("chat-",
                SseEventType.TOOL_CONFIRMATION_REQUEST, request);
        log.info("工具确认请求已发送: requestId={}, toolId={}, riskLevel={}",
                requestId, tool.id(), tool.riskLevel());

        try {
            return future.get(confirmationTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.info("工具确认超时: requestId={}, toolId={}", requestId, tool.id());
            return false;
        } catch (Exception e) {
            log.error("工具确认异常: requestId={}", requestId, e);
            return false;
        } finally {
            pendingConfirmations.remove(requestId);
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
}
