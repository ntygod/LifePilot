package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.config.ProactiveConfigProperties;
import com.lifepilot.agent.proactive.model.TrackingEntry;
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
     * @param typeId 通知类型标识
     */
    public void track(String typeId) {
        pending.put(typeId, new TrackingEntry(typeId, Instant.now()));
        log.debug("开始追踪通知响应: typeId={}", typeId);
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
            var typeId = entry.getKey();
            if (isRelated(typeId, userMessage)) {
                frequencyStateManager.recordAcknowledged(typeId, null);
                toRemove.add(typeId);
                log.debug("通知被确认: typeId={}", typeId);
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

        for (var typeId : expired) {
            pending.remove(typeId);
            frequencyStateManager.recordIgnored(typeId, null);
            log.debug("通知响应超时，标记为忽略: typeId={}", typeId);
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
}
