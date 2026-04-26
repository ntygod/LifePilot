package com.lifepilot.tool.pipeline;

import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.PermissionDecisionEntry;
import com.lifepilot.permission.model.PermissionDecisionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.service.PermissionApprovalService;
import com.lifepilot.permission.service.PermissionRequestFactory;
import com.lifepilot.permission.service.PermissionService;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.*;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 工具执行管线 — 工具调用的唯一入口。
 *
 * <p>管线负责：工具解析 → 参数校验 → 护栏检查 → 幂等检查 → 执行 → 重试 → 返回结果。
 * 轨迹记录由 AgentLoop 通过 ToolResultMeta 完成。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class ToolExecutionPipeline implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);

    private final DynamicToolRegistry toolRegistry;
    private final GuardrailEngine guardrailEngine;
    private final IdempotencyManager idempotencyManager;
    private final PermissionService permissionService;
    private final PermissionRequestFactory permissionRequestFactory;
    private final PermissionApprovalService permissionApprovalService;
    private final long retryInitialDelayMs;
    private final double retryMultiplier;
    private final long retryMaxDelayMs;
    private final ExecutorService virtualThreadExecutor;
    @Nullable
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 便捷构造器 — 不带事件发布器（测试和兼容用）。
     */
    public ToolExecutionPipeline(
            DynamicToolRegistry toolRegistry,
            GuardrailEngine guardrailEngine,
            IdempotencyManager idempotencyManager,
            PermissionService permissionService,
            PermissionRequestFactory permissionRequestFactory,
            PermissionApprovalService permissionApprovalService,
            long retryInitialDelayMs,
            double retryMultiplier,
            long retryMaxDelayMs) {
        this(toolRegistry, guardrailEngine, idempotencyManager,
                permissionService, permissionRequestFactory, permissionApprovalService,
                retryInitialDelayMs, retryMultiplier, retryMaxDelayMs, null);
    }

    public ToolExecutionPipeline(
            DynamicToolRegistry toolRegistry,
            GuardrailEngine guardrailEngine,
            IdempotencyManager idempotencyManager,
            PermissionService permissionService,
            PermissionRequestFactory permissionRequestFactory,
            PermissionApprovalService permissionApprovalService,
            long retryInitialDelayMs,
            double retryMultiplier,
            long retryMaxDelayMs,
            @Nullable ApplicationEventPublisher eventPublisher) {
        this.toolRegistry = toolRegistry;
        this.guardrailEngine = guardrailEngine;
        this.idempotencyManager = idempotencyManager;
        this.permissionService = permissionService;
        this.permissionRequestFactory = permissionRequestFactory;
        this.permissionApprovalService = permissionApprovalService;
        this.retryInitialDelayMs = retryInitialDelayMs;
        this.retryMultiplier = retryMultiplier;
        this.retryMaxDelayMs = retryMaxDelayMs;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.eventPublisher = eventPublisher;
    }

    /**
     * Spring Bean 销毁时关闭虚拟线程池。
     *
     * <p>{@link #close()} 也保留实现 {@link Closeable} 契约（测试 try-with-resources）。</p>
     */
    @PreDestroy
    @Override
    public void close() {
        virtualThreadExecutor.close();
    }

    /**
     * 执行工具调用（无审批上下文）— 便捷重载。
     *
     * @param toolId 工具 ID
     * @param parameters 调用参数
     * @param traceId 轨迹 ID（用于日志关联）
     * @param idempotencyKey 幂等键（可选）
     * @return 结构化执行结果
     */
    public ToolResult execute(String toolId, Map<String, Object> parameters,
                              String traceId, @Nullable String idempotencyKey) {
        return execute(toolId, parameters, traceId, idempotencyKey, null, null);
    }

    /**
     * 执行工具调用（无 context）— 便捷重载。
     *
     * @param toolId 工具 ID
     * @param parameters 调用参数
     * @param traceId 轨迹 ID（用于日志关联）
     * @param idempotencyKey 幂等键（可选）
     * @param streamId SSE 流标识（可选，用于构建审批上下文）
     * @return 结构化执行结果
     */
    public ToolResult execute(String toolId, Map<String, Object> parameters,
                              String traceId, @Nullable String idempotencyKey,
                              @Nullable String streamId) {
        return execute(toolId, parameters, traceId, idempotencyKey, streamId, null);
    }

    /**
     * 执行工具调用 — 管线唯一入口。
     *
     * <p>流程：解析 → 校验 → 护栏 → 幂等 → 执行（含超时+重试） → 返回。</p>
     *
     * @param toolId 工具 ID
     * @param parameters 调用参数
     * @param traceId 轨迹 ID（用于日志关联）
     * @param idempotencyKey 幂等键（可选）
     * @param streamId SSE 流标识（可选，用于构建审批上下文）
     * @param context 请求级上下文（传递 sessionId/channelType 等非 LLM 参数，可选）
     * @return 结构化执行结果
     */
    public ToolResult execute(String toolId, Map<String, Object> parameters,
                              String traceId, @Nullable String idempotencyKey,
                              @Nullable String streamId,
                              @Nullable Map<String, Object> context) {
        Instant start = Instant.now();
        log.debug("管线开始: toolId={}, traceId={}, params={}", toolId, traceId, parameters);

        // 1. 解析工具
        ToolContract tool = toolRegistry.resolve(toolId).orElse(null);
        if (tool == null) {
            log.warn("工具未找到: toolId={}", toolId);
            return ToolResult.error("工具未找到: " + toolId,
                    buildMeta(toolId, start, 0, false, idempotencyKey));
        }

        // 2. 参数预处理、类型强转与校验
        Map<String, Object> effectiveParameters = prepareParameters(toolId, parameters);
        effectiveParameters = tool.inputSchema().coerceParameters(effectiveParameters);
        ToolInput input = new ToolInput(toolId, effectiveParameters, tool.inputSchema(), idempotencyKey, context);
        ValidationResult validation = input.validate();
        if (!validation.isValid()) {
            String errorMsg = ((ValidationResult.Failed) validation).formatForLlm();
            log.warn("参数校验失败: toolId={}", toolId);
            return ToolResult.error(errorMsg,
                    buildMeta(toolId, start, 0, false, idempotencyKey));
        }

        // 3. 权限判定
        PermissionRequest permissionRequest = permissionRequestFactory.create(tool, input, traceId);
        PermissionDecisionEntry permissionDecision = permissionService.evaluateAndRecord(permissionRequest);
        if (permissionDecision.decisionType() == PermissionDecisionType.BLOCKED) {
            log.warn("权限阻断: toolId={}, actionType={}, reason={}",
                    toolId, permissionRequest.actionType(), permissionDecision.reason());
            return ToolResult.error("权限阻断: " + permissionDecision.reason(),
                    buildMeta(toolId, start, 0, false, idempotencyKey));
        }
        if (permissionDecision.decisionType() == PermissionDecisionType.NEEDS_APPROVAL) {
            var approvalContext = buildApprovalContext(streamId, context);
            ExecutionGrant grant = permissionApprovalService.requestApproval(tool, permissionRequest, approvalContext);
            if (grant == null) {
                log.info("工具授权未获批准: toolId={}, actionType={}", toolId, permissionRequest.actionType());
                return ToolResult.error("未获得执行授权",
                        buildMeta(toolId, start, 0, false, idempotencyKey));
            }
        }

        // 4. 护栏检查
        GuardrailResult guardrail = guardrailEngine.checkToolCall(tool, input);
        switch (guardrail) {
            case GuardrailResult.Blocked blocked -> {
                log.warn("护栏拦截: toolId={}, reason={}", toolId, blocked.reason());
                return ToolResult.error("护栏拦截: " + blocked.reason(),
                        buildMeta(toolId, start, 0, false, idempotencyKey));
            }
            case GuardrailResult.NeedsConfirmation _ ->
                    throw new IllegalStateException("护栏层不应再返回确认结果");
            case GuardrailResult.Passed _ -> { /* 通过 */ }
        }

        // 5. 幂等检查
        if (idempotencyKey != null && tool.idempotent()) {
            var cached = idempotencyManager.checkDuplicate(idempotencyKey);
            if (cached.isPresent()) {
                log.debug("幂等命中: toolId={}, key={}", toolId, idempotencyKey);
                return cached.get().toBuilder()
                        .meta(buildMeta(toolId, start, 0, true, idempotencyKey))
                        .build();
            }
        }

        // 6. 执行（含超时 + 重试）
        int maxRetries = tool.budget().maxRetries();
        ToolResult result = executeWithRetry(tool, input, maxRetries);

        // 7. 记录幂等缓存
        if (idempotencyKey != null && tool.idempotent() && result.ok()) {
            idempotencyManager.recordExecution(idempotencyKey, result);
        }

        // 8. 补充元信息
        Duration duration = Duration.between(start, Instant.now());
        ToolResultMeta meta = result.meta().toBuilder()
                .toolId(toolId)
                .duration(duration)
                .idempotencyKey(idempotencyKey)
                .executorType(tool.layer().name())
                .timestamp(start)
                .build();

        // 9. 发布调用事件 — 供观测性 / 审计监听者采集
        publishInvocationEvent(toolId, context, result.ok(), duration.toMillis(), start);

        log.debug("管线完成: toolId={}, ok={}, duration={}ms",
                toolId, result.ok(), duration.toMillis());
        return result.toBuilder().meta(meta).build();
    }

    /**
     * 发布工具调用事件；无 publisher 时静默跳过（测试场景）。
     *
     * <p>事件监听器异常被吞掉 —— 采集链路失败不能影响工具执行结果。</p>
     */
    private void publishInvocationEvent(String toolId, @Nullable Map<String, Object> context,
                                        boolean success, long durationMs, Instant at) {
        if (eventPublisher == null) {
            return;
        }
        String sessionId = null;
        if (context != null) {
            Object raw = context.get(ToolContextKeys.SESSION_ID);
            if (raw instanceof String s && !s.isBlank()) {
                sessionId = s;
            }
        }
        try {
            eventPublisher.publishEvent(new ToolInvocationEvent(toolId, sessionId, success, durationMs, at));
        } catch (Exception e) {
            log.warn("发布工具调用事件失败: toolId={}", toolId, e);
        }
    }

    /**
     * 带重试的执行。
     *
     * @param tool 工具契约
     * @param input 工具输入
     * @param maxRetries 最大重试次数
     * @return 执行结果
     */
    private ToolResult executeWithRetry(ToolContract tool, ToolInput input, int maxRetries) {
        ToolResult lastResult = null;
        long delay = retryInitialDelayMs;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                // RATE_LIMITED 时优先使用 retryAfterMs 作为等待时间
                long actualDelay = delay;
                if (lastResult.status() == ToolResultStatus.RATE_LIMITED) {
                    Object retryAfterMs = lastResult.data().get("retryAfterMs");
                    if (retryAfterMs instanceof Number n && n.longValue() > 0) {
                        actualDelay = n.longValue();
                    }
                }
                log.debug("重试执行: toolId={}, attempt={}/{}, delay={}ms",
                        tool.id(), attempt, maxRetries, actualDelay);
                try {
                    Thread.sleep(actualDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("执行被中断",
                            ToolResultMeta.empty().toBuilder().retryCount(attempt).build());
                }
                delay = Math.min((long) (delay * retryMultiplier), retryMaxDelayMs);
            }

            lastResult = executeWithTimeout(tool, input, tool.budget().timeout());

            if (lastResult.ok() || !isRetryable(lastResult)) {
                return lastResult.toBuilder()
                        .meta(lastResult.meta().toBuilder().retryCount(attempt).build())
                        .build();
            }
        }

        // 所有重试耗尽
        return lastResult != null
                ? lastResult.toBuilder()
                    .meta(lastResult.meta().toBuilder().retryCount(maxRetries).build())
                    .build()
                : ToolResult.error("执行失败，重试耗尽");
    }

    private Map<String, Object> prepareParameters(String toolId, Map<String, Object> parameters) {
        if (!"cron".equals(toolId) || parameters.containsKey("taskId")) {
            return parameters;
        }
        Object action = parameters.get("action");
        if (!(action instanceof String actionName) || !"create".equals(actionName)) {
            return parameters;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(parameters);
        enriched.put("taskId", UUID.randomUUID().toString());
        return Map.copyOf(enriched);
    }

    /**
     * 带超时的执行（使用 Virtual Thread）。
     *
     * @param tool 工具契约
     * @param input 工具输入
     * @param timeout 超时时间
     * @return 执行结果
     */
    private ToolResult executeWithTimeout(ToolContract tool, ToolInput input, Duration timeout) {
        try {
            CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(
                    () -> tool.execute(input),
                    virtualThreadExecutor
            );
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                log.warn("工具执行超时: toolId={}, timeout={}s", tool.id(), timeout.getSeconds());
                return ToolResult.error("执行超时: " + timeout.getSeconds() + "秒");
            }
            log.error("工具执行异常: toolId={}", tool.id(), cause);
            return ToolResult.error("执行异常: " + (cause != null ? cause.getMessage() : "未知错误"));
        } catch (Exception e) {
            log.error("工具执行异常: toolId={}", tool.id(), e);
            return ToolResult.error("执行异常: " + e.getMessage());
        }
    }

    /**
     * 判断结果是否可重试。
     *
     * <p>RATE_LIMITED 状态始终可重试；超时和临时性错误可重试；
     * 参数错误和护栏拦截不可重试。
     * 用户响应超时（交互工具）不可重试 — SSE 断开后重试无意义。</p>
     */
    private boolean isRetryable(ToolResult result) {
        if (result.ok()) {
            return false;
        }
        // 语义状态优先：TRANSIENT_ERROR / RATE_LIMITED 始终可重试
        if (result.status().isRetryable()) {
            return true;
        }
        String error = result.error();
        if (error == null) {
            return false;
        }
        // 用户响应超时（交互工具）不可重试 — SSE 连接断开后重试无意义
        if (error.contains("用户响应超时")) {
            return false;
        }
        return error.contains("超时") || error.contains("timeout")
                || error.contains("连接") || error.contains("connection")
                || error.contains("临时") || error.contains("temporary");
    }

    /**
     * 从 streamId 和请求上下文中提取审批路由信息。
     *
     * <p>Web 端携带 streamId，渠道端携带 channelType/channelInstanceId/userId。
     * 两者不互斥：Web 端也可能携带渠道信息。</p>
     */
    @Nullable
    private Map<String, String> buildApprovalContext(@Nullable String streamId,
                                                     @Nullable Map<String, Object> context) {
        Map<String, String> approvalCtx = new LinkedHashMap<>();
        if (streamId != null && !streamId.isBlank()) {
            approvalCtx.put("streamId", streamId);
        }
        if (context != null) {
            copyIfPresent(context, approvalCtx, ToolContextKeys.CHANNEL_TYPE);
            copyIfPresent(context, approvalCtx, ToolContextKeys.CHANNEL_INSTANCE_ID);
            copyIfPresent(context, approvalCtx, ToolContextKeys.USER_ID);
            copyIfPresent(context, approvalCtx, ToolContextKeys.SESSION_ID);
        }
        return approvalCtx.isEmpty() ? null : Map.copyOf(approvalCtx);
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, String> target, String key) {
        Object value = source.get(key);
        if (value instanceof String s && !s.isBlank()) {
            target.put(key, s);
        }
    }

    /** 构建执行元信息。 */
    private ToolResultMeta buildMeta(String toolId, Instant start, int retryCount,
                                      boolean cacheHit, @Nullable String idempotencyKey) {
        return ToolResultMeta.builder()
                .toolId(toolId)
                .action("execute")
                .duration(Duration.between(start, Instant.now()))
                .tokensUsed(0)
                .cacheHit(cacheHit)
                .idempotencyKey(idempotencyKey)
                .retryCount(retryCount)
                .executorType("UNKNOWN")
                .timestamp(start)
                .build();
    }
}
