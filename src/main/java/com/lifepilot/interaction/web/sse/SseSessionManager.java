package com.lifepilot.interaction.web.sse;

import com.lifepilot.agent.CancellationToken;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.interaction.web.config.WebProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SSE 会话管理器，管理 SseEmitter 生命周期。
 *
 * <p>集中管理 streamId → SseEmitter 映射，提供创建、发送事件、关闭和心跳功能。
 * 心跳调度使用 Virtual Thread 工厂创建的 ScheduledExecutorService。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class SseSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SseSessionManager.class);

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CancellationToken> cancellationTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> chatSessionStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> streamSessions = new ConcurrentHashMap<>();
    /** per-streamId 锁，保护 SseEmitter.send() 的线程安全（SseEmitter 非线程安全） */
    private final ConcurrentHashMap<String, Object> emitterLocks = new ConcurrentHashMap<>();
    private final WebProperties properties;
    private final SharedScheduler sharedScheduler;

    public SseSessionManager(WebProperties properties, SharedScheduler sharedScheduler) {
        this.properties = properties;
        this.sharedScheduler = sharedScheduler;
    }

    /**
     * 注册取消信号令牌，关联到指定 streamId。
     *
     * <p>当 SSE 回调（完成/超时/错误）触发时，自动调用 token.cancel() 通知 AgentLoop 停止执行。</p>
     *
     * @param streamId 流式传输标识
     * @param token    取消信号令牌
     */
    public void registerCancellationToken(String streamId, CancellationToken token) {
        cancellationTokens.put(streamId, token);
        log.debug("CancellationToken 已注册: streamId={}", streamId);
    }

    /**
     * 创建新的 SseEmitter 并注册到管理器。
     *
     * @param streamId 流式传输标识
     * @return 新创建的 SseEmitter
     */
    public SseEmitter createEmitter(String streamId) {
        var emitter = new SseEmitter(properties.sse().timeout());

        emitter.onCompletion(() -> {
            emitters.remove(streamId);
            emitterLocks.remove(streamId);
            cancelToken(streamId);
            clearChatStreamBinding(streamId);
            log.debug("SseEmitter 完成: streamId={}", streamId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(streamId);
            emitterLocks.remove(streamId);
            cancelToken(streamId);
            clearChatStreamBinding(streamId);
            log.info("SseEmitter 超时: streamId={}", streamId);
        });
        emitter.onError(ex -> {
            emitters.remove(streamId);
            emitterLocks.remove(streamId);
            cancelToken(streamId);
            clearChatStreamBinding(streamId);
            log.warn("SseEmitter 异常: streamId={}", streamId, ex);
        });

        emitters.put(streamId, emitter);
        log.debug("SseEmitter 创建成功: streamId={}", streamId);
        return emitter;
    }

    /**
     * 创建通知专用 SseEmitter 并注册到管理器。
     *
     * @param notificationStreamId 通知流标识（notification-{uuid} 前缀）
     * @param timeout              超时时间（毫秒）
     * @return 新创建的 SseEmitter
     */
    public SseEmitter createNotificationEmitter(String notificationStreamId, long timeout) {
        var emitter = new SseEmitter(timeout);

        emitter.onCompletion(() -> {
            emitters.remove(notificationStreamId);
            emitterLocks.remove(notificationStreamId);
            log.debug("通知 SseEmitter 完成: streamId={}", notificationStreamId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(notificationStreamId);
            emitterLocks.remove(notificationStreamId);
            log.debug("通知 SseEmitter 超时: streamId={}", notificationStreamId);
        });
        emitter.onError(ex -> {
            emitters.remove(notificationStreamId);
            emitterLocks.remove(notificationStreamId);
            log.warn("通知 SseEmitter 异常: streamId={}", notificationStreamId, ex);
        });

        emitters.put(notificationStreamId, emitter);
        log.debug("通知 SseEmitter 创建成功: streamId={}", notificationStreamId);
        return emitter;
    }

    /**
     * 广播通知到所有 notification- 前缀的 emitter。
     *
     * @param data 通知事件数据
     */
    public void broadcastNotification(Object data) {
        emitters.forEach((streamId, emitter) -> {
            if (streamId.startsWith("notification-")) {
                try {
                    var event = SseEmitter.event()
                            .name(SseEventType.NOTIFICATION)
                            .data(data);
                    doSend(streamId, emitter, event);
                } catch (IOException e) {
                    log.warn("通知广播失败，关闭连接: streamId={}", streamId);
                    closeEmitter(streamId);
                }
            }
        });
    }

    /**
     * 按前缀广播事件到匹配的 SseEmitter。
     *
     * <p>遍历所有已注册的 emitter，向 streamId 以指定前缀开头的 emitter 发送事件。
     * 单个 emitter 发送失败时关闭该连接，不影响其他 emitter。</p>
     *
     * @param prefix    streamId 前缀（如 "mcp-status-"）
     * @param eventType 事件类型
     * @param data      事件数据
     */
    public void broadcastByPrefix(String prefix, String eventType, Object data) {
        emitters.forEach((streamId, emitter) -> {
            if (streamId.startsWith(prefix)) {
                try {
                    var event = SseEmitter.event()
                            .name(eventType)
                            .data(data);
                    doSend(streamId, emitter, event);
                } catch (IOException e) {
                    log.warn("前缀广播失败，关闭连接: streamId={}, prefix={}", streamId, prefix);
                    closeEmitter(streamId);
                }
            }
        });
    }


    /**
     * 获取已注册的 SseEmitter（不创建新实例）。
     *
     * @param streamId 流式传输标识
     * @return 已注册的 SseEmitter，若不存在则返回 null
     */
    public SseEmitter getEmitter(String streamId) {
        return emitters.get(streamId);
    }

    /**
     * 绑定聊天会话到当前活动的 SSE 流。
     *
     * @param sessionId 会话 ID
     * @param streamId  当前活动流 ID
     */
    public void bindChatSession(String sessionId, String streamId) {
        if (sessionId == null || sessionId.isBlank() || streamId == null || streamId.isBlank()) {
            return;
        }

        String previousStreamId = chatSessionStreams.put(sessionId, streamId);
        streamSessions.put(streamId, sessionId);
        if (previousStreamId != null && !previousStreamId.equals(streamId)) {
            streamSessions.remove(previousStreamId, sessionId);
            // 关闭孤立的旧 emitter，释放资源并触发取消信号通知旧 Agent 停止执行
            closeEmitter(previousStreamId);
            log.info("会话绑定新流，旧流已关闭: sessionId={}, oldStreamId={}, newStreamId={}",
                    sessionId, previousStreamId, streamId);
        }
        log.debug("聊天会话已绑定 SSE 流: sessionId={}, streamId={}", sessionId, streamId);
    }

    /**
     * 查找指定聊天会话当前活动的 SSE 流 ID。
     *
     * @param sessionId 会话 ID
     * @return 活动流 ID；不存在时返回 null
     */
    public String findChatStreamId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String streamId = chatSessionStreams.get(sessionId);
        if (streamId == null) {
            return null;
        }
        if (!emitters.containsKey(streamId)) {
            chatSessionStreams.remove(sessionId, streamId);
            streamSessions.remove(streamId, sessionId);
            return null;
        }
        return streamId;
    }

    /**
     * 向指定 SseEmitter 发送事件。
     *
     * @param streamId  流式传输标识
     * @param eventType 事件类型（token / ui / done / error）
     * @param data      事件数据
     */
    public void sendEvent(String streamId, String eventType, Object data) {
        var emitter = emitters.get(streamId);
        if (emitter == null) {
            log.warn("SseEmitter 不存在，无法发送事件: streamId={}, eventType={}", streamId, eventType);
            return;
        }
        try {
            // 对于 done 事件，如果数据是 Map 类型且未包含 timestamp，自动添加时间戳
            Object eventData = data;
            if (SseEventType.DONE.equals(eventType) && data instanceof Map<?, ?> dataMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> originalMap = (Map<String, Object>) dataMap;
                if (!originalMap.containsKey("timestamp")) {
                    // 防御性拷贝：避免对不可变 Map（Map.of()）执行 put 导致 UnsupportedOperationException
                    var mutableCopy = new java.util.HashMap<>(originalMap);
                    mutableCopy.put("timestamp", Instant.now().toEpochMilli());
                    eventData = mutableCopy;
                }
            }

            @SuppressWarnings("null")
            var event = SseEmitter.event()
                    .name(eventType)
                    .data(eventData);
            doSend(streamId, emitter, event);
        } catch (IOException e) {
            log.warn("SseEmitter 发送事件失败: streamId={}, eventType={}", streamId, eventType, e);
            closeEmitter(streamId);
        }
    }

    /**
     * 线程安全地向 SseEmitter 发送事件。
     *
     * <p>SseEmitter.send() 非线程安全，多线程（Reactor 流、虚拟线程、心跳调度、超时处理器）
     * 可能并发调用同一个 emitter。通过 per-streamId 锁序列化写入。</p>
     */
    private void doSend(String streamId, SseEmitter emitter, SseEmitter.SseEventBuilder event) throws IOException {
        Object lock = emitterLocks.computeIfAbsent(streamId, k -> new Object());
        synchronized (lock) {
            // double-check：closeEmitter 可能在获取锁之前已移除 emitter，
            // 此时 emitter 已 complete()，继续 send 会抛异常
            if (!emitters.containsKey(streamId)) {
                return;
            }
            emitter.send(event);
        }
    }

    /**
     * 延迟关闭指定 SseEmitter。
     *
     * <p>用于流式执行快速失败场景：先发送 ERROR 事件，延迟关闭以确保
     * Controller 有足够时间将 emitter 返回给客户端。</p>
     *
     * @param streamId 流式传输标识
     * @param delayMs  延迟毫秒数
     */
    public void closeEmitterWithDelay(String streamId, long delayMs) {
        sharedScheduler.cleanup().schedule(
                () -> closeEmitter(streamId),
                delayMs, TimeUnit.MILLISECONDS
        );
    }

    /**
     * 关闭并移除指定 SseEmitter。
     *
     * @param streamId 流式传输标识
     */
    public void closeEmitter(String streamId) {
        var emitter = emitters.remove(streamId);
        emitterLocks.remove(streamId);
        cancelToken(streamId);
        clearChatStreamBinding(streamId);
        if (emitter != null) {
            emitter.complete();
            log.debug("SseEmitter 已关闭: streamId={}", streamId);
        }
    }

    /**
     * 启动心跳调度（按 heartbeat-interval 配置发送心跳事件）。
     *
     * <p>对每个活跃 SseEmitter 发送心跳事件，单个 emitter 发送失败不影响其他 emitter。</p>
     */
    public void startHeartbeat() {
        long interval = properties.sse().heartbeatInterval();
        sharedScheduler.heartbeat().scheduleAtFixedRate(() -> {
            emitters.forEach((streamId, emitter) -> {
                try {
                    var event = SseEmitter.event()
                            .name(SseEventType.HEARTBEAT)
                            .data("");
                    doSend(streamId, emitter, event);
                } catch (IOException e) {
                    log.warn("心跳发送失败，关闭连接: streamId={}", streamId, e);
                    closeEmitter(streamId);
                } catch (Exception e) {
                    log.warn("心跳发送异常: streamId={}", streamId, e);
                }
            });
        }, interval, interval, TimeUnit.MILLISECONDS);
        log.info("SSE 心跳调度已启动: interval={}ms", interval);
    }

    /**
     * 取消指定 streamId 关联的 CancellationToken（如已注册）。
     *
     * @param streamId 流式传输标识
     */
    private void cancelToken(String streamId) {
        var token = cancellationTokens.remove(streamId);
        if (token != null) {
            token.cancel();
            log.debug("CancellationToken 已触发取消: streamId={}", streamId);
        }
    }

    /**
     * 关闭所有 SseEmitter 并清理资源。
     */
    public void shutdown() {
        int count = emitters.size();
        emitters.forEach((streamId, emitter) -> {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("关闭 SseEmitter 异常: streamId={}", streamId, e);
            }
        });
        emitters.clear();
        emitterLocks.clear();
        cancellationTokens.clear();
        chatSessionStreams.clear();
        streamSessions.clear();
        log.info("SseSessionManager 已关闭，清理 {} 个连接", count);
    }

    /**
     * 获取当前活跃的 SseEmitter 数量（用于监控和测试）。
     *
     * @return 活跃连接数
     */
    public int activeCount() {
        return emitters.size();
    }

    private void clearChatStreamBinding(String streamId) {
        String sessionId = streamSessions.remove(streamId);
        if (sessionId != null) {
            chatSessionStreams.remove(sessionId, streamId);
            log.debug("聊天会话 SSE 绑定已清理: sessionId={}, streamId={}", sessionId, streamId);
        }
    }
}
