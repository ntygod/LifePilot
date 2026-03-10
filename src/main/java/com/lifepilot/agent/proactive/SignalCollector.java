package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.model.Signal;
import com.lifepilot.agent.proactive.model.SignalBundle;
import com.lifepilot.agent.proactive.signal.SignalSource;
import com.lifepilot.memory.episodic.EpisodicMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 信号收集器 — 遍历所有注册的 {@link SignalSource} Bean 收集信号。
 *
 * <p>每个信号源独立 try-catch，单个信号源故障不阻塞整个信号收集流程。
 * 时间信号（距上次交互时长、最近对话数量）由本类直接从 {@link EpisodicMemory} 获取。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SignalCollector {

    private static final Logger log = LoggerFactory.getLogger(SignalCollector.class);

    private final List<SignalSource> signalSources;
    private final EpisodicMemory episodicMemory;

    public SignalCollector(List<SignalSource> signalSources,
                           EpisodicMemory episodicMemory) {
        this.signalSources = signalSources;
        this.episodicMemory = episodicMemory;
    }

    /**
     * 收集所有信号，返回不可变 SignalBundle。
     *
     * @return 信号包
     */
    public SignalBundle collect() {
        var now = LocalDateTime.now();
        var signals = new ArrayList<Signal>();
        for (SignalSource source : signalSources) {
            try {
                signals.addAll(source.collect());
            } catch (Exception e) {
                log.warn("信号源收集失败: sourceId={}, error={}", source.id(), e.getMessage());
            }
        }
        return SignalBundle.builder()
                .currentTime(now)
                .dayOfWeek(now.getDayOfWeek())
                .timeSinceLastInteraction(collectTimeSinceLastInteraction(Instant.now()))
                .recentConversationCount(collectRecentConversationCount())
                .signals(signals)
                .build();
    }

    /** 收集最近 24 小时对话数量。 */
    private int collectRecentConversationCount() {
        try {
            var recent = episodicMemory.getRecent(100);
            var cutoff = Instant.now().minus(Duration.ofHours(24));
            return (int) recent.stream()
                    .filter(c -> c.createdAt().isAfter(cutoff))
                    .count();
        } catch (Exception e) {
            log.warn("行为信号收集失败: error={}", e.getMessage());
            return 0;
        }
    }

    /** 计算距上次交互的时长。 */
    private Duration collectTimeSinceLastInteraction(Instant now) {
        try {
            var recent = episodicMemory.getRecent(1);
            if (recent.isEmpty()) {
                return Duration.ofDays(999);
            }
            return Duration.between(recent.getFirst().createdAt(), now);
        } catch (Exception e) {
            log.warn("交互时长计算失败: error={}", e.getMessage());
            return Duration.ofDays(999);
        }
    }
}
