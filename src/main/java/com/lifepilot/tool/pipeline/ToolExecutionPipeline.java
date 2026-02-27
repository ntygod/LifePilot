package com.lifepilot.tool.pipeline;

import com.lifepilot.guardrail.GuardrailPolicy;
import com.lifepilot.guardrail.GuardrailResult;
import com.lifepilot.interaction.UserConfirmationService;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolResultMeta;
import com.lifepilot.tool.model.ValidationResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);

    private final DynamicToolRegistry toolRegistry;
    private final GuardrailPolicy guardrailPolicy;
    private final IdempotencyManager idempotencyManager;
    private final UserConfirmationService confirmationService;
    private final long retryInitialDelayMs;
    private final double retryMultiplier;
    private final long retryMaxDelayMs;

    public ToolExecutionPipeline(
            DynamicToolRegistry toolRegistry,
            GuardrailPolicy guardrailPolicy,
            IdempotencyManager idempotencyManager,
            UserConfirmationService confirmationService,
            long retryInitialDelayMs,
            double retryMultiplier,
            long retryMaxDelayMs) {
        this.toolRegistry = toolRegistry;
        this.guardrailPolicy = guardrailPolicy;
        this.idempotencyManager = idempotencyManager;
        this.confirmationService = confirmationService;
        this.retryInitialDelayMs = retryInitialDelayMs;
        this.retryMultiplier = retryMultiplier;
        this.retryMaxDelayMs = retryMaxDelayMs;
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
     * @return 结构化执行结果
     */
    public ToolResult execute(String toolId, Map<String, Object> parameters,
                              String traceId, @Nullable String idempotencyKey) {
        Instant start = Instant.now();
        log.debug("管线开始: toolId={}, traceId={}", toolId, traceId);

        // 1. 解析工具
        ToolContract tool = toolRegistry.resolve(toolId).orElse(null);
        if (tool == null) {
            log.warn("工具未找到: toolId={}", toolId);
            return ToolResult.error("工具未找到: " + toolId,
                    buildMeta(toolId, start, 0, false, idempotencyKey));
        }

        // 2. 参数校验
        ToolInput input = new ToolInput(toolId, parameters, tool.inputSchema(), idempotencyKey);
        ValidationResult validation = input.validate();
        if (!validation.isValid()) {
            String errorMsg = ((ValidationResult.Failed) validation).formatForLlm();
            log.warn("参数校验失败: toolId={}", toolId);
            return ToolResult.error(errorMsg,
                    buildMeta(toolId, start, 0, false, idempotencyKey));
        }

        // 3. 护栏检查
        GuardrailResult guardrail = guardrailPolicy.checkToolCall(tool, input);
        if (guardrail.blocked()) {
            log.warn("护栏拦截: toolId={}, reason={}", toolId, guardrail.reason());
            return ToolResult.error("护栏拦截: " + guardrail.reason(),
                    buildMeta(toolId, start, 0, false, idempotencyKey));
        }
        if (guardrail.requiresConfirmation()) {
            boolean confirmed = confirmationService.requestConfirmation(
                    tool, input, guardrail.confirmationMessage());
            if (!confirmed) {
                log.info("用户拒绝执行: toolId={}", toolId);
                return ToolResult.error("用户拒绝执行",
                        buildMeta(toolId, start, 0, false, idempotencyKey));
            }
        }

        // 4. 幂等检查
        if (idempotencyKey != null && tool.idempotent()) {
            var cached = idempotencyManager.checkDuplicate(idempotencyKey);
            if (cached.isPresent()) {
                log.debug("幂等命中: toolId={}, key={}", toolId, idempotencyKey);
                return cached.get().toBuilder()
                        .meta(buildMeta(toolId, start, 0, true, idempotencyKey))
                        .build();
            }
        }

        // 5. 执行（含超时 + 重试）
        int maxRetries = tool.budget().maxRetries();
        ToolResult result = executeWithRetry(tool, input, maxRetries);

        // 6. 记录幂等缓存
        if (idempotencyKey != null && tool.idempotent() && result.ok()) {
            idempotencyManager.recordExecution(idempotencyKey, result);
        }

        // 7. 补充元信息
        Duration duration = Duration.between(start, Instant.now());
        ToolResultMeta meta = result.meta().toBuilder()
                .toolId(toolId)
                .duration(duration)
                .idempotencyKey(idempotencyKey)
                .executorType(tool.layer().name())
                .timestamp(start)
                .build();

        log.debug("管线完成: toolId={}, ok={}, duration={}ms",
                toolId, result.ok(), duration.toMillis());
        return result.toBuilder().meta(meta).build();
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
                log.debug("重试执行: toolId={}, attempt={}/{}, delay={}ms",
                        tool.id(), attempt, maxRetries, delay);
                try {
                    Thread.sleep(delay);
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
                    Executors.newVirtualThreadPerTaskExecutor()
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
     * <p>超时和临时性错误可重试，参数错误和护栏拦截不可重试。</p>
     */
    private boolean isRetryable(ToolResult result) {
        if (result.ok()) {
            return false;
        }
        String error = result.error();
        if (error == null) {
            return false;
        }
        return error.contains("超时") || error.contains("timeout")
                || error.contains("连接") || error.contains("connection")
                || error.contains("临时") || error.contains("temporary");
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
