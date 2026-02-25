package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.config.ProactiveConfigProperties;
import com.lifepilot.agent.proactive.model.NotificationType;
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
 * <p>使用关键词匹配判断用户消息与通知的相关性。
 * 超过响应窗口的条目标记为忽略。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ResponseTracker {

    private static final Logger log = LoggerFactory.getLogger(ResponseTracker.class);

    private final FrequencyStateManager frequencyStateManager;
    private final ProactiveConfigProperties config;
    private final ConcurrentHashMap<NotificationType, TrackingEntry> pending = new ConcurrentHashMap<>();

    public ResponseTracker(FrequencyStateManager frequencyStateManager,
                            ProactiveConfigProperties config) {
        this.frequencyStateManager = frequencyStateManager;
        this.config = config;
    }

    /**
     * 开始追踪一个已发送通知。
     *
     * @param type 通知类型
     */
    public void track(NotificationType type) {
        pending.put(type, new TrackingEntry(type, Instant.now()));
        log.debug("开始追踪通知响应: type={}", type);
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
        var toRemove = new ArrayList<NotificationType>();
        for (var entry : pending.entrySet()) {
            var type = entry.getKey();
            if (isRelated(type, userMessage)) {
                frequencyStateManager.recordAcknowledged(type);
                toRemove.add(type);
                log.debug("通知被确认: type={}", type);
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
        var expired = new ArrayList<NotificationType>();

        for (var entry : pending.entrySet()) {
            var elapsed = Duration.between(entry.getValue().sentAt(), now).toMillis();
            if (elapsed >= windowMs) {
                expired.add(entry.getKey());
            }
        }

        for (var type : expired) {
            pending.remove(type);
            frequencyStateManager.recordIgnored(type);
            log.debug("通知响应超时，标记为忽略: type={}", type);
        }
    }

    /**
     * 判断用户消息是否与指定通知类型相关（关键词匹配）。
     */
    private boolean isRelated(NotificationType type, String message) {
        return type.keywords().stream().anyMatch(message::contains);
    }
}
