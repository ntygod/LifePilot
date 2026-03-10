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
        var instantNow = Instant.now();
        var signals = new ArrayList<Signal>();
        for (SignalSource source : signalSources) {
            try {
                signals.addAll(source.collect());
            } catch (Exception e) {
                log.warn("信号源收集失败: sourceId={}, error={}", source.id(), e.getMessage());
            }
        }

        // 一次查询同时计算交互时长和最近对话数量，避免重复访问 EpisodicMemory
        var behaviorSignals = collectBehaviorSignals(instantNow);

        return SignalBundle.builder()
                .currentTime(now)
                .dayOfWeek(now.getDayOfWeek())
                .timeSinceLastInteraction(behaviorSignals.timeSinceLastInteraction())
                .recentConversationCount(behaviorSignals.recentConversationCount())
                .signals(signals)
                .build();
    }

    /**
     * 一次查询 EpisodicMemory，同时计算交互时长和最近 24 小时对话数量。
     */
    private BehaviorSignals collectBehaviorSignals(Instant now) {
        try {
            var recent = episodicMemory.getRecent(100);
            // 距上次交互时长
            var timeSinceLastInteraction = recent.isEmpty()
                    ? Duration.ofDays(999)
                    : Duration.between(recent.getFirst().createdAt(), now);
            // 最近 24 小时对话数量
            var cutoff = now.minus(Duration.ofHours(24));
            var recentCount = (int) recent.stream()
                    .filter(c -> c.createdAt().isAfter(cutoff))
                    .count();
            return new BehaviorSignals(timeSinceLastInteraction, recentCount);
        } catch (Exception e) {
            log.warn("行为信号收集失败: error={}", e.getMessage());
            return new BehaviorSignals(Duration.ofDays(999), 0);
        }
    }

    /** 行为信号聚合结果。 */
    private record BehaviorSignals(Duration timeSinceLastInteraction, int recentConversationCount) {}
}
