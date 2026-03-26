package com.lifepilot.generation.client;

import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.adapter.SpringAiProviderAdapter;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Spring AI 适配器的生成服务客户端。
 *
 * @author zsg
 * @since 2026-03-24
 */
public class SpringAiGenerationClient implements GenerationServiceClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiGenerationClient.class);

    private final ModelServiceEntity service;
    private final SpringAiProviderAdapter adapter;

    public SpringAiGenerationClient(ModelServiceEntity service, SpringAiProviderAdapter adapter) {
        this.service = service;
        this.adapter = adapter;
    }

    @Override
    public LlmResponse call(String prompt, @Nullable String outputSchema, @Nullable Duration timeoutOverride) {
        Duration timeout = effectiveTimeout(timeoutOverride);
        return executeWithTimeout("text", () -> adapter.call(prompt, outputSchema, timeout), timeout);
    }

    @Override
    public <T> T callEntity(String prompt, Class<T> responseType, @Nullable Duration timeoutOverride) {
        Duration timeout = effectiveTimeout(timeoutOverride);
        return executeWithTimeout("structured", () -> adapter.callEntity(prompt, responseType), timeout);
    }

    @Override
    public Optional<ChatClient> chatClient() {
        return adapter.chatClient();
    }

    @Override
    public ChatModel chatModel() {
        return adapter.chatModel();
    }

    @Override
    public Flux<String> stream(String prompt) {
        return adapter.stream(prompt);
    }

    private Duration effectiveTimeout(@Nullable Duration timeoutOverride) {
        return timeoutOverride != null ? timeoutOverride : Duration.ofSeconds(service.timeoutSeconds());
    }

    private <T> T executeWithTimeout(String operation, Callable<T> action, Duration timeout) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(action);
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("生成服务调用超时: serviceId={}, model={}, operation={}, timeoutSeconds={}",
                        service.id(), service.modelName(), operation, timeout.toSeconds());
                throw new RuntimeException("生成服务调用超时: " + timeout.toSeconds() + "s", e);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                log.warn("生成服务调用被中断: serviceId={}, model={}, operation={}",
                        service.id(), service.modelName(), operation);
                throw new RuntimeException("生成服务调用被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                log.debug("生成服务调用执行失败: serviceId={}, model={}, operation={}, causeType={}, message={}",
                        service.id(),
                        service.modelName(),
                        operation,
                        cause != null ? cause.getClass().getSimpleName() : "unknown",
                        cause != null ? cause.getMessage() : e.getMessage());
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException("生成服务调用失败", cause);
            }
        }
    }
}
