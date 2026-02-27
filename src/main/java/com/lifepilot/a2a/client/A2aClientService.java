package com.lifepilot.a2a.client;

import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A2A Client 远程调用服务。
 *
 * <p>使用 Spring RestClient 进行 HTTP 调用，支持 Agent 发现、
 * 消息发送、Task 查询和取消。所有远程调用失败均降级处理，
 * 不抛出异常。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class A2aClientService {

    private static final Logger log = LoggerFactory.getLogger(A2aClientService.class);

    private final RestClient restClient;
    private final A2aProperties properties;

    public A2aClientService(A2aProperties properties) {
        this.properties = properties;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getClient().getConnectTimeoutSeconds() * 1000);
        requestFactory.setReadTimeout(properties.getClient().getReadTimeoutSeconds() * 1000);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 发现远程 Agent 能力。
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
                return Optional.of(card);
            }
            log.warn("远程 Agent 发现返回空响应: url={}", agentUrl);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("远程 Agent 发现失败: url={}, error={}", agentUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 向远程 Agent 发送消息。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @param message  A2A 消息
     * @return A2aTask（调用失败返回包含 FAILED 状态的 Task）
     */
    public A2aTask sendMessage(String agentUrl, A2aMessage message) {
        try {
            A2aTask task = restClient.post()
                    .uri(agentUrl + "/api/a2a/message/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .body(A2aTask.class);
            if (task != null) {
                log.info("远程消息发送成功: url={}, taskId={}", agentUrl, task.id());
                return task;
            }
            log.warn("远程消息发送返回空响应: url={}", agentUrl);
            return buildFailedTask(message, "远程 Agent 返回空响应");
        } catch (Exception e) {
            log.warn("远程消息发送失败: url={}, error={}", agentUrl, e.getMessage());
            return buildFailedTask(message, "远程调用失败: " + e.getMessage());
        }
    }

    /**
     * 查询远程 Task 状态。
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @param taskId   Task ID
     * @return Task（查询失败返回空 Optional）
     */
    public Optional<A2aTask> getTask(String agentUrl, String taskId) {
        try {
            A2aTask task = restClient.get()
                    .uri(agentUrl + "/api/a2a/tasks/" + taskId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(A2aTask.class);
            return Optional.ofNullable(task);
        } catch (Exception e) {
            log.warn("远程 Task 查询失败: url={}, taskId={}, error={}", agentUrl, taskId, e.getMessage());
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
        try {
            restClient.post()
                    .uri(agentUrl + "/api/a2a/tasks/" + taskId + "/cancel")
                    .retrieve()
                    .toBodilessEntity();
            log.info("远程 Task 取消成功: url={}, taskId={}", agentUrl, taskId);
            return true;
        } catch (Exception e) {
            log.warn("远程 Task 取消失败: url={}, taskId={}, error={}", agentUrl, taskId, e.getMessage());
            return false;
        }
    }

    /**
     * 构建失败状态的 Task（用于远程调用失败时的降级响应）。
     */
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
