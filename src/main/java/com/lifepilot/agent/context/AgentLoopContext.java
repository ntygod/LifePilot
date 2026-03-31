package com.lifepilot.agent.context;

import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Set<String> activatedSkillToolIds = ConcurrentHashMap.newKeySet();
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

    /** 非流式模式构造。 */
    public AgentLoopContext() {
        this.requestReceivedAt = Instant.now();
        this.sseManager = null;
        this.streamId = null;
        this.turnId = null;
    }

    /** 流式模式构造。 */
    public AgentLoopContext(@Nullable SseSessionManager sseManager,
                            @Nullable String streamId) {
        this.requestReceivedAt = Instant.now();
        this.sseManager = sseManager;
        this.streamId = streamId;
        this.turnId = null;
    }

    /** 流式模式构造（含 turnId）。 */
    public AgentLoopContext(@Nullable SseSessionManager sseManager,
                            @Nullable String streamId,
                            @Nullable String turnId) {
        this.requestReceivedAt = Instant.now();
        this.sseManager = sseManager;
        this.streamId = streamId;
        this.turnId = turnId;
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

    /** Skill 激活时调用 — 将 skill 的 suggestedTools 加入当前请求的工具集。 */
    public void addActivatedSkillTools(Collection<String> toolIds) {
        if (toolIds != null) {
            activatedSkillToolIds.addAll(toolIds);
        }
    }

    /** 获取当前请求中已激活的 skill 工具 ID 集合。 */
    public Set<String> getActivatedSkillToolIds() {
        return Collections.unmodifiableSet(activatedSkillToolIds);
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
