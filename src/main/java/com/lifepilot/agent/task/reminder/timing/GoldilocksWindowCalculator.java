package com.lifepilot.agent.task.reminder.timing;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderCandidate;
import com.lifepilot.agent.task.reminder.ReminderCandidateType;
import com.lifepilot.agent.task.reminder.ReminderExecutionRepository;
import com.lifepilot.agent.task.reminder.ReminderReplaySample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Goldilocks 时效窗口计算器 — 对带有明确截止时间的提醒候选，
 * 根据用户历史响应延迟估算"最晚多晚发还有意义"的窗口。
 *
 * <p>参考：CHI 2025 Goldilocks Time Window（arXiv:2504.09332）：
 * {@code windowEnd = deadline - p80(userResponseLatency)}。超过窗口再发等于打扰。</p>
 *
 * <p>本地个人助手场景下延迟估算使用用户自己的历史响应数据（从
 * {@link ReminderExecutionRepository#findReplaySamplesByUserIdSince} 读取）；
 * 样本不足时回退到配置默认值 {@code proactiveTimingDefaultResponseLatencyMinutes}（默认 30 分钟）。</p>
 *
 * <p>当前仅对 {@link ReminderCandidateType#DUE_SOON} 与
 * {@link ReminderCandidateType#PREPARATION_WINDOW} 两类启用（这两类有明确
 * {@code relevantAt}）；其他类型不做窗口检查。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class GoldilocksWindowCalculator {

    private static final Logger log = LoggerFactory.getLogger(GoldilocksWindowCalculator.class);

    /** 查历史样本的时间窗口 — 30 天。 */
    private static final Duration SAMPLE_LOOKBACK = Duration.ofDays(30);

    /** 单次查询样本数量上限。 */
    private static final int SAMPLE_LIMIT = 200;

    private final ReminderExecutionRepository executionRepository;
    private final AgentConfigProperties.TaskConfig taskConfig;

    public GoldilocksWindowCalculator(ReminderExecutionRepository executionRepository,
                                       AgentConfigProperties.TaskConfig taskConfig) {
        this.executionRepository = Objects.requireNonNull(executionRepository);
        this.taskConfig = Objects.requireNonNull(taskConfig);
    }

    /**
     * 计算该用户对应 candidateType 的 Goldilocks window 结束时间。
     *
     * @param userId   用户 ID
     * @param deadline 候选的 {@code relevantAt}（deadline / event time）
     * @param type     候选类型（用于选择估算策略，当前统一）
     * @return windowEnd = deadline - estimatedLatency
     */
    public Instant computeWindowEnd(String userId, Instant deadline, ReminderCandidateType type) {
        Objects.requireNonNull(deadline, "deadline 不能为空");
        if (!taskConfig.isProactiveTimingWindowEnabled()) {
            return deadline;
        }
        Duration latency = estimateResponseLatency(userId);
        return deadline.minus(latency);
    }

    /**
     * 判定某候选是否已超出 Goldilocks 窗口。
     *
     * <p>简化语义：{@code suggestedAt} 是该候选最"合适的"提醒时间点。Goldilocks 判断为
     * {@code now > suggestedAt + latency_buffer}。已超过建议时间点加上典型用户响应延迟
     * 的宽限，视为"再提醒也来不及"。</p>
     *
     * @param userId    用户 ID（用于估算典型响应延迟）
     * @param candidate 当前候选
     * @param now       当前时间
     * @return true 当候选应当被跳过（WINDOW_CLOSED）
     */
    public boolean isWindowClosed(String userId, ReminderCandidate candidate, Instant now) {
        if (candidate == null) return false;
        if (!taskConfig.isProactiveTimingWindowEnabled()) return false;
        if (candidate.suggestedAt() == null) return false;
        if (!isSupportedType(candidate.type())) return false;

        Duration latency = estimateResponseLatency(userId);
        // 加权 safety：latency × safetyFactor（越小越快 close）
        float safety = clampSafety(taskConfig.getProactiveTimingResponseLatencyP80Percentile());
        long graceSeconds = (long) (latency.getSeconds() * safety);
        Instant effectiveDeadline = candidate.suggestedAt().plusSeconds(graceSeconds);

        boolean closed = now.isAfter(effectiveDeadline);
        if (closed) {
            log.debug("Goldilocks: windowClosed user={}, topic={}, now={}, suggestedAt={}, grace={}s",
                    userId, candidate.topicKey(), now, candidate.suggestedAt(), graceSeconds);
        }
        return closed;
    }

    /** 从历史 replay samples 估算 p80 响应延迟。 */
    Duration estimateResponseLatency(String userId) {
        int minSamples = Math.max(1, taskConfig.getProactiveTimingResponseLatencyMinSamples());
        Duration fallback = Duration.ofMinutes(
                Math.max(1, taskConfig.getProactiveTimingDefaultResponseLatencyMinutes()));
        if (userId == null || userId.isBlank()) return fallback;

        Instant since = Instant.now().minus(SAMPLE_LOOKBACK);
        List<ReminderReplaySample> samples;
        try {
            samples = executionRepository.findReplaySamplesByUserIdSince(userId, since, SAMPLE_LIMIT);
        } catch (Exception e) {
            log.debug("Goldilocks: 样本查询失败, fallback 默认延迟, user={}, error={}", userId, e.getMessage());
            return fallback;
        }
        if (samples == null || samples.size() < minSamples) return fallback;

        // 连续 acted 样本之间的时间差作为"响应延迟"的经验观察
        List<Duration> durations = new ArrayList<>();
        ReminderReplaySample prev = null;
        for (var s : samples) {
            if (prev != null && s.acted() && prev.acted()) {
                Duration d = Duration.between(prev.decidedAt(), s.decidedAt()).abs();
                // 过滤异常值：低于 1 分钟（时钟抖动）或超过 24 小时（跨天非同场景）
                if (d.toMinutes() >= 1 && d.toHours() < 24) {
                    durations.add(d);
                }
            }
            prev = s;
        }
        if (durations.size() < minSamples) return fallback;

        Collections.sort(durations);
        int p80Index = Math.min(durations.size() - 1, (int) Math.ceil(durations.size() * 0.8) - 1);
        p80Index = Math.max(0, p80Index);
        return durations.get(p80Index);
    }

    private boolean isSupportedType(ReminderCandidateType type) {
        return type == ReminderCandidateType.DUE_SOON
                || type == ReminderCandidateType.PREPARATION_WINDOW;
    }

    private float clampSafety(float value) {
        if (value < 0.1f) return 0.1f;
        if (value > 1.0f) return 1.0f;
        return value;
    }
}
