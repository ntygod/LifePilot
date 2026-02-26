package com.lifepilot.tool.pipeline;

import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等键管理器（内存版）。
 *
 * <p>使用 ConcurrentHashMap 实现内存级幂等缓存。
 * 持久化到 SQLite 在后续 spec 中实现。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class IdempotencyManager {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyManager.class);

    /** 进程内缓存：idempotencyKey → ToolResult。 */
    private final ConcurrentHashMap<String, ToolResult> cache = new ConcurrentHashMap<>();

    /**
     * 检查是否已执行过相同的幂等调用。
     *
     * @param key 幂等键
     * @return 如果已执行，返回缓存的结果
     */
    public Optional<ToolResult> checkDuplicate(String key) {
        ToolResult cached = cache.get(key);
        if (cached != null) {
            log.debug("幂等命中: key={}", key);
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    /**
     * 记录已执行的幂等调用。
     *
     * @param key 幂等键
     * @param result 执行结果
     */
    public void recordExecution(String key, ToolResult result) {
        cache.put(key, result);
        log.debug("幂等记录保存: key={}", key);
    }

}
