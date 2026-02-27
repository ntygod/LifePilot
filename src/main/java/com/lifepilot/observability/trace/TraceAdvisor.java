package com.lifepilot.observability.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.time.Instant;

/**
 * 追踪 Advisor — 通过 Spring AI Advisor 模式横切注入 LLM 调用追踪。
 *
 * <p>在 adviseCall 中记录 LLM 调用的开始时间，调用完成后从 ChatResponse 提取
 * Token 使用量、模型信息、完成原因等，构建 {@link LlmCallStep} 并通过
 * {@link TraceRecorder#recordStep} 记录到当前 TraceContext。</p>
 *
 * <p>优先级设为 {@code HIGHEST_PRECEDENCE + 100}，在 GuardrailAdvisor 之后执行。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TraceAdvisor.class);

    private final TraceRecorder traceRecorder;

    public TraceAdvisor(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @Override
    public String getName() {
        return "TraceAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var startTime = Instant.now();

        try {
            var response = chain.nextCall(request);
            recordLlmStep(startTime, response, null);
            return response;
        } catch (Exception e) {
            recordLlmStep(startTime, null, e);
            throw e;
        }
    }

    /**
     * 从 ChatClientResponse 提取信息，构建 LlmCallStep 并记录。
     */
    private void recordLlmStep(Instant startTime, ChatClientResponse response, Exception error) {
        traceRecorder.currentContext().ifPresent(ctx -> {
            try {
                var now = Instant.now();
                var duration = Duration.between(startTime, now);

                // 从 ChatResponse 提取 Token 使用量和模型信息
                int inputTokens = 0;
                int outputTokens = 0;
                String modelId = "unknown";
                String finishReason = null;

                if (response != null && response.chatResponse() != null) {
                    var chatResponse = response.chatResponse();
                    var metadata = chatResponse.getMetadata();

                    // 提取模型 ID
                    if (metadata.getModel() != null) {
                        modelId = metadata.getModel();
                    }

                    // 提取 Token 使用量
                    Usage usage = metadata.getUsage();
                    if (usage != null) {
                        inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                        outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                    }

                    // 提取完成原因
                    if (chatResponse.getResult() != null && chatResponse.getResult().getMetadata() != null) {
                        finishReason = chatResponse.getResult().getMetadata().getFinishReason();
                    }
                }

                // 异常时记录错误信息到 finishReason
                if (error != null) {
                    finishReason = "error: " + error.getMessage();
                }

                var step = new LlmCallStep(
                        ctx.steps().size(),
                        startTime,
                        duration,
                        "unknown",  // providerId 在 Advisor 层无法获取，由上层补充
                        modelId,
                        "chat",     // 默认场景
                        inputTokens,
                        outputTokens,
                        duration,   // latency 等于 duration
                        false,      // cacheHit 在 Advisor 层无法判断
                        0.0,        // temperature 在 Advisor 层无法获取
                        finishReason
                );

                traceRecorder.recordStep(ctx, step);
                log.debug("LLM 调用追踪记录: model={}, inputTokens={}, outputTokens={}, latency={}ms",
                        modelId, inputTokens, outputTokens, duration.toMillis());

            } catch (Exception e) {
                log.warn("LLM 调用追踪记录失败: error={}", e.getMessage());
            }
        });
    }
}
