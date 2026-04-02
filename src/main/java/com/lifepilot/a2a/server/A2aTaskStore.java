package com.lifepilot.a2a.server;

import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A Task 内存存储。
 *
 * <p>使用 ConcurrentHashMap 存储 A2aTask，支持 TTL 自动清理。
 * 不持久化到 SQLite（A2A Task 是短期会话状态）。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class A2aTaskStore {

    private static final Logger log = LoggerFactory.getLogger(A2aTaskStore.class);

    private final ConcurrentHashMap<String, A2aTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> createdAt = new ConcurrentHashMap<>();
    private final A2aProperties properties;

    public A2aTaskStore(A2aProperties properties) {
        this.properties = properties;
    }

    /**
     * 创建新 Task（初始状态 SUBMITTED）。
     *
     * @param message 触发消息
     * @return 新建的 Task
     */
    public A2aTask create(A2aMessage message) {
        String taskId = UUID.randomUUID().toString();
        String contextId = message.contextId() != null
                ? message.contextId()
                : UUID.randomUUID().toString();
        var status = new A2aTaskStatus(A2aTaskState.SUBMITTED, null, Instant.now().toString());
        var task = new A2aTask(taskId, contextId, status, List.of(message), null, null);
        tasks.put(taskId, task);
        createdAt.put(taskId, Instant.now());
        log.debug("A2A Task 创建: taskId={}, contextId={}", taskId, contextId);
        return task;
    }

    /**
     * 解析或创建 Task：若消息携带已有 taskId 则追加 history，否则创建新 Task。
     *
     * @param message A2A 消息
     * @return 已有或新建的 Task
     */
    public A2aTask resolveOrCreate(A2aMessage message) {
        if (message.taskId() != null) {
            var existing = find(message.taskId());
            if (existing.isPresent()) {
                return appendHistory(message.taskId(), message);
            }
        }
        return create(message);
    }

    /** 按 ID 查找 Task。 */
    public Optional<A2aTask> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** 按 contextId 列出所有关联 Task。 */
    public List<A2aTask> listByContextId(String contextId) {
        return tasks.values().stream()
                .filter(t -> t.contextId().equals(contextId))
                .toList();
    }

    /**
     * 更新 Task 状态。
     *
     * @param taskId   Task ID
     * @param newState 新状态
     * @param message  状态消息（可空）
     * @return 更新后的 Task
     */
    public A2aTask updateStatus(String taskId, A2aTaskState newState, @Nullable String message) {
        return tasks.compute(taskId, (id, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Task 不存在: " + taskId);
            }
            var statusMsg = message != null
                    ? new A2aMessage(UUID.randomUUID().toString(), A2aRole.AGENT,
                            List.of(new A2aPart.Text(message, null)), taskId, existing.contextId(), null)
                    : null;
            var newStatus = new A2aTaskStatus(newState, statusMsg, Instant.now().toString());
            return new A2aTask(existing.id(), existing.contextId(), newStatus,
                    existing.history(), existing.artifacts(), existing.metadata());
        });
    }

    /**
     * 向 Task 添加 Artifact。
     *
     * @param taskId   Task ID
     * @param artifact 产出物
     * @return 更新后的 Task
     */
    public A2aTask addArtifact(String taskId, A2aArtifact artifact) {
        return tasks.compute(taskId, (id, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Task 不存在: " + taskId);
            }
            var artifacts = new ArrayList<>(existing.artifacts() != null ? existing.artifacts() : List.<A2aArtifact>of());
            artifacts.add(artifact);
            return new A2aTask(existing.id(), existing.contextId(), existing.status(),
                    existing.history(), List.copyOf(artifacts), existing.metadata());
        });
    }

    /**
     * 向 Task 追加 history 消息。
     *
     * <p>超过 maxHistoryLength 时移除最早的消息。</p>
     */
    public A2aTask appendHistory(String taskId, A2aMessage message) {
        int maxLen = properties.getTask().getMaxHistoryLength();
        return tasks.compute(taskId, (id, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Task 不存在: " + taskId);
            }
            var history = new ArrayList<>(existing.history() != null ? existing.history() : List.<A2aMessage>of());
            history.add(message);
            // 超出限制时移除最早的消息
            while (history.size() > maxLen) {
                history.removeFirst();
            }
            return new A2aTask(existing.id(), existing.contextId(), existing.status(),
                    List.copyOf(history), existing.artifacts(), existing.metadata());
        });
    }

    /**
     * 取消 Task（原子操作）。
     *
     * <p>允许取消 SUBMITTED / WORKING / INPUT_REQUIRED / AUTH_REQUIRED 状态的 Task，
     * 终态 Task 返回 false。使用 computeIfPresent 保证并发安全。</p>
     */
    public boolean cancel(String taskId) {
        var success = new java.util.concurrent.atomic.AtomicBoolean(false);
        tasks.computeIfPresent(taskId, (id, existing) -> {
            var currentState = existing.status().state();
            if (currentState != A2aTaskState.SUBMITTED
                    && currentState != A2aTaskState.WORKING
                    && currentState != A2aTaskState.INPUT_REQUIRED
                    && currentState != A2aTaskState.AUTH_REQUIRED) {
                return existing;
            }
            var statusMsg = new A2aMessage(UUID.randomUUID().toString(), A2aRole.AGENT,
                    List.of(new A2aPart.Text("Task 已取消", null)), taskId, existing.contextId(), null);
            var newStatus = new A2aTaskStatus(A2aTaskState.CANCELED, statusMsg, Instant.now().toString());
            success.set(true);
            return new A2aTask(existing.id(), existing.contextId(), newStatus,
                    existing.history(), existing.artifacts(), existing.metadata());
        });
        return success.get();
    }

    /**
     * 清理过期 Task（由定时任务调用）。
     *
     * @return 清理数量
     */
    public int cleanupExpired() {
        int ttlMinutes = properties.getTask().getTtlMinutes();
        Instant cutoff = Instant.now().minusSeconds(ttlMinutes * 60L);
        var expiredIds = createdAt.entrySet().stream()
                .filter(e -> e.getValue().isBefore(cutoff))
                .map(Map.Entry::getKey)
                .toList();
        expiredIds.forEach(id -> {
            tasks.remove(id);
            createdAt.remove(id);
        });
        if (!expiredIds.isEmpty()) {
            log.info("A2A Task 过期清理: count={}", expiredIds.size());
        }
        return expiredIds.size();
    }
}
