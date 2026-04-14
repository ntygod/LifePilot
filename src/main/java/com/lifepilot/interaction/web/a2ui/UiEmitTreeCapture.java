package com.lifepilot.interaction.web.a2ui;

import com.lifepilot.interaction.web.model.A2uiComponentTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ui.emit 组件树捕获桥接器 — 在工具执行器和编排器之间传递 A2UI 组件树。
 *
 * <p>解决的问题：{@link UiEmitToolExecutor} 通过 SSE 将组件树推送到前端后，
 * 编排器需要将同一棵树持久化到数据库以支持历史回放。
 * 该桥接器通过 streamId 作为 key，在工具执行器写入、编排器读取之间传递组件树。</p>
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} 保证并发安全。
 * 生命周期：{@link #poll(String)} 取走后自动清理，不会造成内存泄漏。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class UiEmitTreeCapture {

    private static final Logger log = LoggerFactory.getLogger(UiEmitTreeCapture.class);

    private final ConcurrentHashMap<String, A2uiComponentTree> captured = new ConcurrentHashMap<>();

    /**
     * 捕获组件树，关联到指定 streamId。
     *
     * <p>如果同一 streamId 已有捕获的树，会被覆盖（以最后一次 ui.emit 调用为准）。</p>
     *
     * @param streamId SSE 流标识
     * @param tree     经校验的组件树
     */
    public void capture(String streamId, A2uiComponentTree tree) {
        captured.put(streamId, tree);
        log.debug("已捕获 A2UI 组件树: streamId={}, componentCount={}", streamId, tree.components().size());
    }

    /**
     * 取出并移除指定 streamId 关联的组件树。
     *
     * <p>取出后自动从内部存储中删除，保证不会内存泄漏。
     * 如果没有对应的树则返回 null。</p>
     *
     * @param streamId SSE 流标识
     * @return 捕获的组件树，如果没有则返回 null
     */
    @Nullable
    public A2uiComponentTree poll(String streamId) {
        return captured.remove(streamId);
    }
}
