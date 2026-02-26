package com.lifepilot.interaction.web.sse;

import com.lifepilot.interaction.web.config.WebProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private final WebProperties properties;
    private final ScheduledExecutorService heartbeatScheduler;

    public SseSessionManager(WebProperties properties) {
        this.properties = properties;
        this.heartbeatScheduler = Executors.newScheduledThreadPool(
                1, Thread.ofVirtual().name("sse-heartbeat-", 0).factory()
        );
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
            log.debug("SseEmitter 完成: streamId={}", streamId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(streamId);
            log.info("SseEmitter 超时: streamId={}", streamId);
        });
        emitter.onError(ex -> {
            emitters.remove(streamId);
            log.warn("SseEmitter 异常: streamId={}", streamId, ex);
        });

        emitters.put(streamId, emitter);
        log.debug("SseEmitter 创建成功: streamId={}", streamId);
        return emitter;
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
            var event = SseEmitter.event()
                    .name(eventType)
                    .data(data);
            emitter.send(event);
        } catch (IOException e) {
            log.warn("SseEmitter 发送事件失败: streamId={}, eventType={}", streamId, eventType, e);
            closeEmitter(streamId);
        }
    }

    /**
     * 关闭并移除指定 SseEmitter。
     *
     * @param streamId 流式传输标识
     */
    public void closeEmitter(String streamId) {
        var emitter = emitters.remove(streamId);
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
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            emitters.forEach((streamId, emitter) -> {
                try {
                    var event = SseEmitter.event()
                            .name("heartbeat")
                            .data("");
                    emitter.send(event);
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
     * 停止所有心跳并关闭所有 SseEmitter。
     */
    public void shutdown() {
        heartbeatScheduler.shutdown();
        try {
            if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        int count = emitters.size();
        emitters.forEach((streamId, emitter) -> {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("关闭 SseEmitter 异常: streamId={}", streamId, e);
            }
        });
        emitters.clear();
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
}
