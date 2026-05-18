package com.lifepilot.agent.context;

import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.sse.SseEventBuffer;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 循环请求作用域上下文 — 替代单例 Bean 上的可变实例字段。
 *
 * <p>每次 run/runStreaming 调用创建新实例，随方法参数传递，
 * 消除单例 Bean 上可变状态导致的并发安全问题。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class AgentLoopContext {

    private final List<MediaDataExtractor.MediaItem> collectedToolMedia = new ArrayList<>();
    private final List<String> injectedEntityIds = new ArrayList<>();
    private final List<com.lifepilot.interaction.model.ArtifactRef> collectedArtifactRefs = new ArrayList<>();
    /**
     * 旁路收集 — 工具执行结束后由 ToolBridge 推入；ToolExecutionCoordinator 在
     * {@code persistTranscriptToolResult(...)} 时取走，写入 session_artifacts。
     * 由于 ToolBridge 没有持久化能力，必须经此中转才能让 artifacts 落到表上。
     */
    private final java.util.Queue<com.lifepilot.tool.model.ToolArtifact> pendingToolArtifacts =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Instant requestReceivedAt;
    private volatile @Nullable A2uiComponentTree lastCollectedA2uiTree;
    private volatile boolean visibleOutputEmitted;
    private volatile @Nullable Instant firstReasoningEventAt;
    private volatile @Nullable Instant modelStreamStartAt;
    private volatile @Nullable Instant firstModelTokenAt;
    private volatile @Nullable Instant firstTokenSseAt;

    // 流式模式下的 SSE 上下文（非流式模式为 null）
    @Nullable private final SseSessionManager sseManager;
    @Nullable private final String streamId;
    @Nullable private final String turnId;
    @Nullable private final SseEventBuffer eventBuffer;

    /** 非流式模式构造。 */
    public AgentLoopContext() {
        this.requestReceivedAt = Instant.now();
        this.sseManager = null;
        this.streamId = null;
        this.turnId = null;
        this.eventBuffer = null;
    }

    /** 流式模式构造。 */
    public AgentLoopContext(@Nullable SseSessionManager sseManager,
                            @Nullable String streamId,
                            @Nullable String turnId,
                            @Nullable SseEventBuffer eventBuffer) {
        this.requestReceivedAt = Instant.now();
        this.sseManager = sseManager;
        this.streamId = streamId;
        this.turnId = turnId;
        this.eventBuffer = eventBuffer;
    }

    /** 获取 SSE 会话管理器（非流式模式返回 null）。 */
    @Nullable
    public SseSessionManager getSseManager() { return sseManager; }

    /** 获取 SSE 流 ID（非流式模式返回 null）。 */
    @Nullable
    public String getStreamId() { return streamId; }

    /** 获取 SSE turnId（非流式模式返回 null）。 */
    @Nullable
    public String getTurnId() { return turnId; }

    /** 获取 SSE 事件缓冲区（未启用或非流式模式返回 null）。 */
    @Nullable
    public SseEventBuffer getEventBuffer() { return eventBuffer; }

    /** 收集工具产生的媒体数据。 */
    public void addToolMedia(MediaDataExtractor.MediaItem item) {
        collectedToolMedia.add(item);
    }

    /** 批量收集工具产生的媒体数据。 */
    public void addAllToolMedia(List<MediaDataExtractor.MediaItem> items) {
        collectedToolMedia.addAll(items);
    }

    /** 获取已收集的工具媒体数据（防御性拷贝）。 */
    public List<MediaDataExtractor.MediaItem> getCollectedToolMedia() {
        return List.copyOf(collectedToolMedia);
    }

    /** 清空已收集的工具媒体数据。 */
    public void clearToolMedia() {
        collectedToolMedia.clear();
    }

    /** 判断是否有已收集的工具媒体数据。 */
    public boolean hasToolMedia() {
        return !collectedToolMedia.isEmpty();
    }

    /** 添加经验注入的实体 ID。 */
    public void addInjectedEntityIds(List<String> ids) {
        injectedEntityIds.addAll(ids);
    }

    /** 获取已注入的实体 ID 列表（防御性拷贝）。 */
    public List<String> getInjectedEntityIds() {
        return List.copyOf(injectedEntityIds);
    }

    /**
     * 收集 Agent 主循环本轮产生的会话产物引用 —— 由 {@code ToolExecutionCoordinator}
     * 在工具执行结束、写入 {@code session_artifacts} 后调用。
     *
     * <p>这些 ArtifactRef 最终会被 ResponseAssembler 注入到 {@code GatewayResponse.artifactRefs}，
     * 让 {@code ChannelDeliveryDispatcher} 按渠道差异化分发文件消息。</p>
     *
     * @param refs 本次工具调用产生的产物引用列表
     */
    public void addArtifactRefs(List<com.lifepilot.interaction.model.ArtifactRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        collectedArtifactRefs.addAll(refs);
    }

    /** 获取本轮已收集的所有 ArtifactRef（防御性拷贝）。 */
    public List<com.lifepilot.interaction.model.ArtifactRef> getCollectedArtifactRefs() {
        return List.copyOf(collectedArtifactRefs);
    }

    /**
     * 接收 {@code ToolBridgeAgentToolProvider} 旁路推送的 ToolArtifact —— 由 ReactAgentLoop
     * 通过 {@code agentToolProvider.getToolCallbacks(state, streamId, this::addArtifactRefsAsToolArtifacts)}
     * 注入。
     *
     * <p>这些 ToolArtifact 会被暂存在内部队列；{@code ToolExecutionCoordinator} 在
     * {@code persistTranscriptToolResult(...)} 时调用 {@link #drainPendingToolArtifacts()}
     * 取走并写入 session_artifacts。</p>
     */
    public void addArtifactRefsAsToolArtifacts(List<com.lifepilot.tool.model.ToolArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return;
        }
        pendingToolArtifacts.addAll(artifacts);
    }

    /**
     * 取走待持久化的 ToolArtifact 列表并清空队列；调用方负责后续 session_artifacts 写入。
     */
    public List<com.lifepilot.tool.model.ToolArtifact> drainPendingToolArtifacts() {
        if (pendingToolArtifacts.isEmpty()) {
            return List.of();
        }
        List<com.lifepilot.tool.model.ToolArtifact> drained = new ArrayList<>(pendingToolArtifacts.size());
        com.lifepilot.tool.model.ToolArtifact item;
        while ((item = pendingToolArtifacts.poll()) != null) {
            drained.add(item);
        }
        return drained;
    }

    /** 获取最近收集的 A2UI 组件树。 */
    @Nullable
    public A2uiComponentTree getLastCollectedA2uiTree() {
        return lastCollectedA2uiTree;
    }

    /** 获取请求进入主链路的时间点。 */
    public Instant getRequestReceivedAt() {
        return requestReceivedAt;
    }

    /** 设置最近收集的 A2UI 组件树。 */
    public void setLastCollectedA2uiTree(@Nullable A2uiComponentTree tree) {
        this.lastCollectedA2uiTree = tree;
    }

    /** 记录首个真实推理事件发送时间。 */
    public void markFirstReasoningEvent(Instant instant) {
        if (firstReasoningEventAt == null) {
            synchronized (this) {
                if (firstReasoningEventAt == null) {
                    firstReasoningEventAt = instant;
                }
            }
        }
    }

    /** 在首次用户可见 token 到来前，记录当前模型流开始时间。 */
    public void markModelStreamStarted(Instant instant) {
        if (modelStreamStartAt == null) {
            synchronized (this) {
                if (modelStreamStartAt == null) {
                    modelStreamStartAt = instant;
                }
            }
        }
    }

    /** 记录模型侧首个 token 到达时间。 */
    public void markFirstModelToken(Instant instant) {
        if (firstModelTokenAt == null) {
            synchronized (this) {
                if (firstModelTokenAt == null) {
                    firstModelTokenAt = instant;
                }
            }
        }
    }

    /** 记录首个 SSE TOKEN 事件实际发出时间。 */
    public void markFirstTokenSse(Instant instant) {
        if (firstTokenSseAt == null) {
            synchronized (this) {
                if (firstTokenSseAt == null) {
                    firstTokenSseAt = instant;
                }
            }
        }
    }

    /** 构建流式体验时序指标（毫秒）。 */
    public Map<String, Long> streamingTimingsMs() {
        var timings = new LinkedHashMap<String, Long>();
        putTimingMs(timings, "requestReceivedToFirstReasoningEventMs",
                requestReceivedAt, firstReasoningEventAt);
        putTimingMs(timings, "requestReceivedToFirstTokenSseMs",
                requestReceivedAt, firstTokenSseAt);
        putTimingMs(timings, "modelStreamStartToFirstTokenMs",
                modelStreamStartAt, firstModelTokenAt);
        return timings.isEmpty() ? Map.of() : Map.copyOf(timings);
    }

    /** 标记已向前端发出用户可见输出。 */
    public void markVisibleOutputEmitted() {
        this.visibleOutputEmitted = true;
    }

    /** 是否已经向前端发出用户可见输出。 */
    public boolean hasVisibleOutputEmitted() {
        return visibleOutputEmitted;
    }

    private void putTimingMs(Map<String, Long> timings,
                             String key,
                             @Nullable Instant start,
                             @Nullable Instant end) {
        if (start == null || end == null || end.isBefore(start)) {
            return;
        }
        timings.put(key, Duration.between(start, end).toMillis());
    }
}
