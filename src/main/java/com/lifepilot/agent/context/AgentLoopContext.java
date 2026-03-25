package com.lifepilot.agent.context;

import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

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
    private volatile @Nullable A2uiComponentTree lastCollectedA2uiTree;
    private volatile boolean visibleOutputEmitted;

    // 流式模式下的 SSE 上下文（非流式模式为 null）
    @Nullable private final SseSessionManager sseManager;
    @Nullable private final String streamId;
    @Nullable private final String turnId;

    /** 非流式模式构造。 */
    public AgentLoopContext() {
        this.sseManager = null;
        this.streamId = null;
        this.turnId = null;
    }

    /** 流式模式构造。 */
    public AgentLoopContext(@Nullable SseSessionManager sseManager,
                            @Nullable String streamId) {
        this.sseManager = sseManager;
        this.streamId = streamId;
        this.turnId = null;
    }

    /** 流式模式构造（含 turnId）。 */
    public AgentLoopContext(@Nullable SseSessionManager sseManager,
                            @Nullable String streamId,
                            @Nullable String turnId) {
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

    /** 获取最近收集的 A2UI 组件树。 */
    @Nullable
    public A2uiComponentTree getLastCollectedA2uiTree() {
        return lastCollectedA2uiTree;
    }

    /** 设置最近收集的 A2UI 组件树。 */
    public void setLastCollectedA2uiTree(@Nullable A2uiComponentTree tree) {
        this.lastCollectedA2uiTree = tree;
    }

    /** 标记已向前端发出用户可见输出。 */
    public void markVisibleOutputEmitted() {
        this.visibleOutputEmitted = true;
    }

    /** 是否已经向前端发出用户可见输出。 */
    public boolean hasVisibleOutputEmitted() {
        return visibleOutputEmitted;
    }
}
