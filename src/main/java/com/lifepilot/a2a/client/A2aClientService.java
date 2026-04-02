package com.lifepilot.a2a.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.*;
import com.lifepilot.mcp.protocol.JsonRpcMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A2A Client 远程调用服务。
 *
 * <p>使用 Spring RestClient 进行 HTTP 调用，支持 Agent 发现、
 * 消息发送、Task 查询和取消。集成熔断器和 Micrometer 指标。
 * 优先使用 JSON-RPC 2.0 协议，降级兼容旧 REST 端点。
 * 所有远程调用失败均降级处理，不抛出异常。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class A2aClientService {

    private static final Logger log = LoggerFactory.getLogger(A2aClientService.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final RestClient restClient;
    private final A2aProperties properties;
    private final A2aCircuitBreakerRegistry circuitBreakerRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong jsonRpcIdGenerator = new AtomicLong(1);

    private final Counter discoverSuccessCounter;
    private final Counter discoverFailedCounter;
    private final Counter sendSuccessCounter;
    private final Counter sendFailedCounter;
    private final Counter circuitBreakerRejectedCounter;
    private final Timer requestTimer;

    public A2aClientService(A2aProperties properties,
                            A2aCircuitBreakerRegistry circuitBreakerRegistry,
                            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getClient().getConnectTimeoutSeconds() * 1000);
        requestFactory.setReadTimeout(properties.getClient().getReadTimeoutSeconds() * 1000);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();

        this.discoverSuccessCounter = meterRegistry.counter("a2a.client.requests.total", "operation", "discover", "status", "success");
        this.discoverFailedCounter = meterRegistry.counter("a2a.client.requests.total", "operation", "discover", "status", "failed");
        this.sendSuccessCounter = meterRegistry.counter("a2a.client.requests.total", "operation", "send", "status", "success");
        this.sendFailedCounter = meterRegistry.counter("a2a.client.requests.total", "operation", "send", "status", "failed");
        this.circuitBreakerRejectedCounter = meterRegistry.counter("a2a.client.circuit_breaker.rejected");
        this.requestTimer = meterRegistry.timer("a2a.client.request.duration");
    }

    /**
     * 发现远程 Agent 能力（公开端点，不发送 API Key）。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @return Agent Card（获取失败返回空 Optional）
     */
    public Optional<A2aAgentCard> discoverAgent(String agentUrl) {
        try {
            A2aAgentCard card = restClient.get()
                    .uri(agentUrl + "/.well-known/agent.json")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(A2aAgentCard.class);
            if (card != null) {
                log.info("远程 Agent 发现成功: url={}, name={}", agentUrl, card.name());
                discoverSuccessCounter.increment();
                return Optional.of(card);
            }
            log.warn("远程 Agent 发现返回空响应: url={}", agentUrl);
            discoverFailedCounter.increment();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("远程 Agent 发现失败: url={}, error={}", agentUrl, e.getMessage());
            discoverFailedCounter.increment();
            return Optional.empty();
        }
    }

    /**
     * 向远程 Agent 发送消息（优先 JSON-RPC，降级 REST）。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @param message  A2A 消息
     * @return A2aTask（调用失败返回包含 FAILED 状态的 Task）
     */
    public A2aTask sendMessage(String agentUrl, A2aMessage message) {
        if (!circuitBreakerRegistry.isCallPermitted(agentUrl)) {
            log.warn("远程 Agent 熔断中，跳过调用: url={}", agentUrl);
            circuitBreakerRejectedCounter.increment();
            return buildFailedTask(message, "远程 Agent 熔断中，暂时不可用");
        }

        return requestTimer.record(() -> {
            try {
                A2aTask task = sendViaJsonRpc(agentUrl, message);
                if (task != null) {
                    circuitBreakerRegistry.recordSuccess(agentUrl);
                    sendSuccessCounter.increment();
                    return task;
                }
                // JSON-RPC 返回空，降级 REST
                task = sendViaRest(agentUrl, message);
                circuitBreakerRegistry.recordSuccess(agentUrl);
                sendSuccessCounter.increment();
                return task;
            } catch (Exception e) {
                log.warn("远程消息发送失败: url={}, error={}", agentUrl, e.getMessage());
                circuitBreakerRegistry.recordFailure(agentUrl);
                sendFailedCounter.increment();
                return buildFailedTask(message, "远程调用失败: " + e.getMessage());
            }
        });
    }

    /**
     * 查询远程 Task 状态。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @param taskId   Task ID
     * @return Task（查询失败返回空 Optional）
     */
    public Optional<A2aTask> getTask(String agentUrl, String taskId) {
        if (!circuitBreakerRegistry.isCallPermitted(agentUrl)) {
            circuitBreakerRejectedCounter.increment();
            return Optional.empty();
        }
        try {
            A2aTask task = restClient.get()
                    .uri(agentUrl + "/api/a2a/tasks/{taskId}", taskId)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> addApiKey(h, agentUrl))
                    .retrieve()
                    .body(A2aTask.class);
            circuitBreakerRegistry.recordSuccess(agentUrl);
            return Optional.ofNullable(task);
        } catch (Exception e) {
            log.warn("远程 Task 查询失败: url={}, taskId={}, error={}", agentUrl, taskId, e.getMessage());
            circuitBreakerRegistry.recordFailure(agentUrl);
            return Optional.empty();
        }
    }

    /**
     * 取消远程 Task。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @param taskId   Task ID
     * @return 是否取消成功
     */
    public boolean cancelTask(String agentUrl, String taskId) {
        if (!circuitBreakerRegistry.isCallPermitted(agentUrl)) {
            circuitBreakerRejectedCounter.increment();
            return false;
        }
        try {
            restClient.post()
                    .uri(agentUrl + "/api/a2a/tasks/{taskId}/cancel", taskId)
                    .headers(h -> addApiKey(h, agentUrl))
                    .retrieve()
                    .toBodilessEntity();
            log.info("远程 Task 取消成功: url={}, taskId={}", agentUrl, taskId);
            circuitBreakerRegistry.recordSuccess(agentUrl);
            return true;
        } catch (Exception e) {
            log.warn("远程 Task 取消失败: url={}, taskId={}, error={}", agentUrl, taskId, e.getMessage());
            circuitBreakerRegistry.recordFailure(agentUrl);
            return false;
        }
    }

    // ── JSON-RPC 调用 ──

    /** 通过 JSON-RPC 2.0 发送消息。 */
    @Nullable
    private A2aTask sendViaJsonRpc(String agentUrl, A2aMessage message) {
        try {
            long id = jsonRpcIdGenerator.getAndIncrement();
            var rpcRequest = JsonRpcMessage.request(id, "tasks/send",
                    Map.of("message", message));

            JsonRpcMessage rpcResponse = restClient.post()
                    .uri(agentUrl + "/api/a2a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> addApiKey(h, agentUrl))
                    .body(rpcRequest)
                    .retrieve()
                    .body(JsonRpcMessage.class);

            if (rpcResponse != null && rpcResponse.result() != null) {
                A2aTask task = objectMapper.convertValue(rpcResponse.result(), A2aTask.class);
                log.info("远程 JSON-RPC 消息发送成功: url={}, taskId={}", agentUrl, task.id());
                return task;
            }
            return null;
        } catch (Exception e) {
            log.debug("JSON-RPC 调用失败，降级到 REST: url={}, error={}", agentUrl, e.getMessage());
            return null;
        }
    }

    /** 通过旧 REST 端点发送消息。 */
    private A2aTask sendViaRest(String agentUrl, A2aMessage message) {
        A2aTask task = restClient.post()
                .uri(agentUrl + "/api/a2a/message/send")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> addApiKey(h, agentUrl))
                .body(message)
                .retrieve()
                .body(A2aTask.class);
        if (task != null) {
            log.info("远程 REST 消息发送成功: url={}, taskId={}", agentUrl, task.id());
            return task;
        }
        log.warn("远程消息发送返回空响应: url={}", agentUrl);
        return buildFailedTask(message, "远程 Agent 返回空响应");
    }

    // ── 辅助方法 ──

    /** 为请求添加 API Key Header（如果已配置）。 */
    private void addApiKey(org.springframework.http.HttpHeaders headers, String agentUrl) {
        String apiKey = resolveApiKey(agentUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set(API_KEY_HEADER, apiKey);
        }
    }

    /** 从配置中查找指定远程 Agent 的 API Key。 */
    @Nullable
    private String resolveApiKey(String agentUrl) {
        return properties.getClient().getRemoteAgentKeys().get(agentUrl);
    }

    /** 构建失败状态的 Task（用于远程调用失败时的降级响应）。 */
    private A2aTask buildFailedTask(A2aMessage message, String errorMessage) {
        var status = new A2aTaskStatus(
                A2aTaskState.FAILED,
                new A2aMessage(
                        UUID.randomUUID().toString(),
                        A2aRole.AGENT,
                        List.of(new A2aPart.Text(errorMessage, null)),
                        null, null, null
                ),
                Instant.now().toString()
        );
        return new A2aTask(
                UUID.randomUUID().toString(),
                message.contextId() != null ? message.contextId() : UUID.randomUUID().toString(),
                status,
                null, null, null
        );
    }
}
