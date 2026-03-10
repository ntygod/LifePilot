package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.config.ProactiveConfigProperties;
import com.lifepilot.agent.proactive.model.TrackingEntry;
import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户响应追踪器 — 追踪通知响应并反馈给 FrequencyStateManager。
 *
 * <p>使用 {@link NotificationTypeRegistry} 查询关键词进行用户消息相关性匹配。
 * 超过响应窗口的条目标记为忽略。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ResponseTracker {

    private static final Logger log = LoggerFactory.getLogger(ResponseTracker.class);

    private final FrequencyStateManager frequencyStateManager;
    private final ProactiveConfigProperties config;
    private final NotificationTypeRegistry typeRegistry;
    private final ConcurrentHashMap<String, TrackingEntry> pending = new ConcurrentHashMap<>();

    public ResponseTracker(FrequencyStateManager frequencyStateManager,
                           ProactiveConfigProperties config,
                           NotificationTypeRegistry typeRegistry) {
        this.frequencyStateManager = frequencyStateManager;
        this.config = config;
        this.typeRegistry = typeRegistry;
    }

    /**
     * 开始追踪一个已发送通知。
     *
     * @param typeId    通知类型标识
     * @param subjectId 主体标识（可选）
     */
    public void track(String typeId, @Nullable String subjectId) {
        var cacheKey = buildCacheKey(typeId, subjectId);
        pending.put(cacheKey, new TrackingEntry(typeId, subjectId, Instant.now()));
        log.debug("开始追踪通知响应: typeId={}, subjectId={}", typeId, subjectId);
    }

    /**
     * 用户交互时调用 — 检查待追踪通知是否被响应。
     *
     * @param userMessage 用户消息
     */
    public void onUserInteraction(String userMessage) {
        if (pending.isEmpty()) {
            return;
        }

        // 遍历待追踪条目，检查关键词相关性
        var toRemove = new ArrayList<String>();
        for (var entry : pending.entrySet()) {
            var tracking = entry.getValue();
            if (isRelated(tracking.typeId(), userMessage)) {
                frequencyStateManager.recordAcknowledged(tracking.typeId(), tracking.subjectId());
                toRemove.add(entry.getKey());
                log.debug("通知被确认: typeId={}, subjectId={}", tracking.typeId(), tracking.subjectId());
            }
        }
        toRemove.forEach(pending::remove);
    }

    /**
     * 清理过期追踪条目 — 超过响应窗口的标记为忽略。
     */
    public void cleanupExpired() {
        if (pending.isEmpty()) {
            return;
        }

        var windowMs = (long) config.getResponseWindowMinutes() * 60_000L;
        var now = Instant.now();
        var expired = new ArrayList<String>();

        for (var entry : pending.entrySet()) {
            var elapsed = Duration.between(entry.getValue().sentAt(), now).toMillis();
            if (elapsed >= windowMs) {
                expired.add(entry.getKey());
            }
        }

        for (var cacheKey : expired) {
            var tracking = pending.remove(cacheKey);
            if (tracking != null) {
                frequencyStateManager.recordIgnored(tracking.typeId(), tracking.subjectId());
                log.debug("通知响应超时，标记为忽略: typeId={}, subjectId={}", tracking.typeId(), tracking.subjectId());
            }
        }
    }

    /**
     * 判断用户消息是否与指定通知类型相关（关键词匹配）。
     *
     * <p>从 {@link NotificationTypeRegistry} 查询关键词，未找到时返回 false。</p>
     *
     * @param typeId  通知类型标识
     * @param message 用户消息
     * @return 是否相关
     */
    private boolean isRelated(String typeId, String message) {
        return typeRegistry.resolve(typeId)
                .map(def -> def.keywords().stream().anyMatch(message::contains))
                .orElse(false);
    }

    /**
     * 构建缓存键，与 FrequencyStateManager 保持一致。
     */
    private String buildCacheKey(String typeId, @Nullable String subjectId) {
        return subjectId != null && !subjectId.isEmpty() ? typeId + ":" + subjectId : typeId;
    }
}
