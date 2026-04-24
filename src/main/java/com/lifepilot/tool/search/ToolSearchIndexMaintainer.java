package com.lifepilot.tool.search;

import com.lifepilot.tool.event.ToolRegistryEvent;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.search.cache.SchemaCache;
import com.lifepilot.tool.search.cache.SearchResultCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

/**
 * 监听 ToolRegistryEvent 增量维护 tool_search_index + 失效相关缓存。
 *
 * <p>ToolsRegistered 批量 upsert；ToolsUnregistered 批量 delete；
 * ToolConflictDetected 仅日志记录，不动索引（已由 registry 自己处理）。
 * Schema 缓存按 toolId 精确失效，Search 结果缓存全表清（粗粒度）。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchIndexMaintainer {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchIndexMaintainer.class);

    private final ToolSearchIndexBuilder builder;
    private final DynamicToolRegistry registry;
    private final SchemaCache schemaCache;
    private final SearchResultCache searchCache;

    public ToolSearchIndexMaintainer(
            ToolSearchIndexBuilder builder,
            DynamicToolRegistry registry,
            SchemaCache schemaCache,
            SearchResultCache searchCache) {
        this.builder = builder;
        this.registry = registry;
        this.schemaCache = schemaCache;
        this.searchCache = searchCache;
    }

    @EventListener
    public void onToolsRegistered(ToolRegistryEvent.ToolsRegistered event) {
        for (String toolId : event.toolIds()) {
            registry.resolve(toolId).ifPresentOrElse(
                    tool -> {
                        try {
                            builder.upsert(tool);
                            schemaCache.invalidate(toolId);
                        } catch (Exception e) {
                            log.error("索引 upsert 失败: toolId={}", toolId, e);
                        }
                    },
                    () -> log.warn("ToolsRegistered 事件引用的工具未找到: toolId={}", toolId)
            );
        }
        if (!event.toolIds().isEmpty()) {
            searchCache.invalidateAll();
            log.debug("索引增量 upsert: count={}, source={}", event.toolIds().size(), event.source());
        }
    }

    @EventListener
    public void onToolsUnregistered(ToolRegistryEvent.ToolsUnregistered event) {
        for (String toolId : event.toolIds()) {
            try {
                builder.delete(toolId);
                schemaCache.invalidate(toolId);
            } catch (Exception e) {
                log.error("索引 delete 失败: toolId={}", toolId, e);
            }
        }
        if (!event.toolIds().isEmpty()) {
            searchCache.invalidateAll();
            log.debug("索引增量 delete: count={}, source={}", event.toolIds().size(), event.source());
        }
    }

    @EventListener
    public void onToolConflict(ToolRegistryEvent.ToolConflictDetected event) {
        // 冲突由 registry 自行解析；此处只记录，不动索引
        log.info("检测到工具注册冲突: toolId={}, resolution={}", event.toolId(), event.resolution());
    }
}
