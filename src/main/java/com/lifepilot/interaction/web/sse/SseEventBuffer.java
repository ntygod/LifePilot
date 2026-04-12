package com.lifepilot.interaction.web.sse;

import com.lifepilot.interaction.web.config.WebProperties.SseBufferProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 事件缓冲区 — 在生产者和 SSE 派发器之间插入有界异步队列，平滑流式输出速率。
 *
 * <p>每个 SSE 流（streamId）拥有独立的缓冲区实例。内部虚拟线程以自适应速率排空队列，
 * 吸收 LLM 生成速率方差（快速 token 涌入 vs 工具调用沉默期）。</p>
 *
 * <h3>自适应排空策略</h3>
 * <ul>
 *     <li>队列深度 &gt; highWaterMark → 加速排空（~83 events/sec）</li>
 *     <li>队列深度正常 → 稳态排空（~50 events/sec）</li>
 *     <li>队列深度 &lt; lowWaterMark → 减速排空（~25 events/sec），拉伸内容</li>
 *     <li>前瞻扫描到工具调用 → 进一步减速（~10 events/sec），缓和过渡</li>
 *     <li>队列为空 → 注入心跳事件，填补工具调用空白期</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-12
 */
public class SseEventBuffer {

    private static final Logger log = LoggerFactory.getLogger(SseEventBuffer.class);

    /**
     * 缓冲区内部事件封装。
     *
     * @param eventType SSE 事件类型
     * @param data      事件数据
     * @param terminal  是否为终结事件（DONE / ERROR），触发同步排空
     */
    record SseEvent(String eventType, Object data, boolean terminal) {}

    private final String streamId;
    private final LinkedBlockingQueue<SseEvent> queue;
    private final SseSessionManager sseManager;
    private final SseBufferProperties config;
    private final Thread drainThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建事件缓冲区并启动排空虚拟线程。
     *
     * @param streamId   SSE 流标识
     * @param sseManager SSE 会话管理器（排空目标）
     * @param config     缓冲区配置
     */
    public SseEventBuffer(String streamId, SseSessionManager sseManager, SseBufferProperties config) {
        this.streamId = streamId;
        this.sseManager = sseManager;
        this.config = config;
        this.queue = new LinkedBlockingQueue<>(config.queueCapacity());
        this.drainThread = Thread.ofVirtual()
                .name("sse-buffer-drain-" + streamId)
                .start(this::drainLoop);
        log.debug("事件缓冲区已创建: streamId={}, capacity={}", streamId, config.queueCapacity());
    }

    /**
     * 将事件入队（非终结事件）。
     *
     * <p>队列满时等待 offerTimeoutMs，超时则丢弃并记录警告。</p>
     *
     * @param eventType SSE 事件类型
     * @param data      事件数据
     * @return true 入队成功；false 队列满或已关闭
     */
    public boolean offer(String eventType, Object data) {
        if (closed.get()) {
            log.warn("缓冲区已关闭，丢弃事件: streamId={}, eventType={}", streamId, eventType);
            return false;
        }
        try {
            boolean accepted = queue.offer(
                    new SseEvent(eventType, data, false),
                    config.offerTimeoutMs(), TimeUnit.MILLISECONDS
            );
            if (!accepted) {
                log.warn("事件缓冲区已满，丢弃事件: streamId={}, eventType={}, depth={}",
                        streamId, eventType, queue.size());
            }
            return accepted;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 将终结事件入队（DONE / ERROR），触发排空线程同步排空所有前序事件后派发。
     *
     * @param eventType SSE 事件类型（DONE 或 ERROR）
     * @param data      事件数据
     * @return true 入队成功
     */
    public boolean offerTerminal(String eventType, Object data) {
        if (closed.get()) {
            // 已关闭时直接派发终结事件，确保前端收到
            sseManager.sendEvent(streamId, eventType, data);
            return true;
        }
        try {
            boolean accepted = queue.offer(
                    new SseEvent(eventType, data, true),
                    config.offerTimeoutMs(), TimeUnit.MILLISECONDS
            );
            if (!accepted) {
                // 队列满时强制派发终结事件
                log.warn("终结事件入队失败（队列满），强制派发: streamId={}, eventType={}", streamId, eventType);
                sseManager.sendEvent(streamId, eventType, data);
                close();
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sseManager.sendEvent(streamId, eventType, data);
            return true;
        }
    }

    /** 关闭缓冲区，中断排空线程。 */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        drainThread.interrupt();
        log.debug("事件缓冲区已关闭: streamId={}", streamId);
    }

    /** 缓冲区是否已关闭。 */
    public boolean isClosed() {
        return closed.get();
    }

    /** 当前队列深度（监控用）。 */
    public int queueDepth() {
        return queue.size();
    }

    // ==================== 排空循环 ====================

    /**
     * 排空循环 — 虚拟线程入口。
     *
     * <p>以自适应速率从队列取出事件并通过 sseManager 派发。
     * 遇到终结事件时先同步排空所有前序事件，再派发终结事件并关闭 emitter。</p>
     */
    private void drainLoop() {
        log.debug("排空线程已启动: streamId={}", streamId);
        try {
            while (!closed.get()) {
                SseEvent event = queue.poll(config.gapHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);

                if (event == null) {
                    // 队列为空 — 注入心跳填补工具调用空白期
                    if (!closed.get()) {
                        injectHeartbeat();
                    }
                    continue;
                }

                if (event.terminal()) {
                    // 终结事件 — 先排空所有前序事件，再派发
                    flushAll();
                    dispatch(event);
                    // 先标记关闭，避免 closeEmitter 回调链触发冗余 interrupt
                    closed.set(true);
                    sseManager.closeEmitter(streamId);
                    return;
                }

                dispatch(event);

                // 自适应节奏控制
                long sleepMs = computeDrainIntervalMs();
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            }
        } catch (InterruptedException e) {
            // 被 close() 中断，正常退出
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("排空线程异常退出: streamId={}", streamId, e);
        } finally {
            closed.set(true);
            log.debug("排空线程已退出: streamId={}", streamId);
        }
    }

    /** 派发单个事件到 SseSessionManager。 */
    private void dispatch(SseEvent event) {
        sseManager.sendEvent(streamId, event.eventType(), event.data());
    }

    /**
     * 立即排空队列中所有非终结事件（无节奏控制）。
     *
     * <p>终结事件到达前的"冲刺"：把剩余 token 全部派发，确保 DONE 是最后一个事件。</p>
     */
    private void flushAll() {
        SseEvent event;
        while ((event = queue.poll()) != null) {
            if (!event.terminal()) {
                dispatch(event);
            } else {
                // 终结事件放回队列头部（不应出现，防御性处理）
                log.warn("flushAll 遇到额外终结事件，直接派发: streamId={}, eventType={}",
                        streamId, event.eventType());
                dispatch(event);
            }
        }
    }

    /** 注入心跳事件，填补工具调用空白期。 */
    private void injectHeartbeat() {
        sseManager.sendEvent(streamId, SseEventType.HEARTBEAT, "");
    }

    // ==================== 自适应速率控制 ====================

    /**
     * 根据队列深度和前瞻扫描计算排空间隔。
     *
     * <p>队列深 → 加速追赶；队列浅 → 减速拉伸；前瞻到工具调用 → 进一步减速缓和过渡。</p>
     */
    private long computeDrainIntervalMs() {
        int depth = queue.size();
        if (depth > config.highWaterMark()) {
            return config.fastDrainIntervalMs();
        }
        if (depth > config.lowWaterMark()) {
            return config.normalDrainIntervalMs();
        }
        if (peekForToolCall()) {
            return config.preLookaheadIntervalMs();
        }
        return config.slowDrainIntervalMs();
    }

    /**
     * 前瞻扫描队列，检测是否有即将到来的工具调用。
     *
     * <p>利用 LinkedBlockingQueue 的弱一致性迭代器（不出队）扫描 REASONING 事件，
     * 若发现 type=TOOL_CALL 或 type=PROGRESS 则返回 true，指示排空速率减慢。</p>
     */
    private boolean peekForToolCall() {
        for (SseEvent event : queue) {
            if (!SseEventType.REASONING.equals(event.eventType())) {
                continue;
            }
            if (event.data() instanceof Map<?, ?> map) {
                Object inner = map.get("event");
                if (inner instanceof Map<?, ?> detail) {
                    if (detail.get("type") instanceof String type
                            && ("TOOL_CALL".equals(type) || "PROGRESS".equals(type))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
