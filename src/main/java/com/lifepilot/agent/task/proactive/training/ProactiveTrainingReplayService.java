package com.lifepilot.agent.task.proactive.training;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderExecutionRepository;
import com.lifepilot.agent.task.reminder.ReminderReplaySample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 主动训练回放服务 — 从历史反馈构造 {@link ProactiveFewShotSample}。
 *
 * <p>数据源：直接复用既有 {@link ReminderExecutionRepository#findReplaySamplesByUserIdSince}，
 * 不新建 SQL；正负例分层采样后保存到 {@link ProactiveFewShotLibrary}。</p>
 *
 * <p>本服务只负责**生产**样例库，不修改 Gate 3 prompt。Gate 3 的消费由下一 spec
 * {@code proactive-timing-cot} 接入。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class ProactiveTrainingReplayService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveTrainingReplayService.class);

    private final ReminderExecutionRepository executionRepository;
    private final AgentConfigProperties.TaskConfig taskConfig;

    public ProactiveTrainingReplayService(ReminderExecutionRepository executionRepository,
                                          AgentConfigProperties.TaskConfig taskConfig) {
        this.executionRepository = Objects.requireNonNull(executionRepository);
        this.taskConfig = Objects.requireNonNull(taskConfig);
    }

    /**
     * 构造指定用户的 few-shot 样例列表（正负例分层）。
     *
     * @param userId 用户 ID
     * @param since  起点时间
     * @param limit  底层查询样本数量上限
     * @return 不重排、按 positive/negative 顺序拼接的样例列表
     */
    public List<ProactiveFewShotSample> buildFewShotSamples(String userId, Instant since, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return List.of();
        List<ReminderReplaySample> samples =
                executionRepository.findReplaySamplesByUserIdSince(userId, since, limit);
        if (samples.isEmpty()) return List.of();

        float posT = taskConfig.getProactiveTrainingPositiveRewardThreshold();
        float negT = taskConfig.getProactiveTrainingNegativeRewardThreshold();
        int topKp = Math.max(0, taskConfig.getProactiveTrainingTopKPositive());
        int topKn = Math.max(0, taskConfig.getProactiveTrainingTopKNegative());

        List<ProactiveFewShotSample> positives = samples.stream()
                .filter(s -> s.historicalReward() >= posT)
                .sorted(Comparator.comparingDouble(ReminderReplaySample::historicalReward).reversed())
                .limit(topKp)
                .map(s -> toSample(s, true))
                .toList();

        List<ProactiveFewShotSample> negatives = samples.stream()
                .filter(s -> s.historicalReward() <= negT)
                .sorted(Comparator.comparingDouble(ReminderReplaySample::historicalReward))
                .limit(topKn)
                .map(s -> toSample(s, false))
                .toList();

        List<ProactiveFewShotSample> combined = new ArrayList<>(positives.size() + negatives.size());
        combined.addAll(positives);
        combined.addAll(negatives);
        log.debug("训练回放: user={}, total={}, pos={}, neg={}",
                userId, samples.size(), positives.size(), negatives.size());
        return combined;
    }

    private ProactiveFewShotSample toSample(ReminderReplaySample s, boolean positive) {
        return new ProactiveFewShotSample(
                s.candidateType().name(),
                truncate(s.title(), 80),
                s.historicalAction(),
                s.historicalReward(),
                digestContext(s),
                s.decidedAt(),
                positive
        );
    }

    /**
     * 构造上下文摘要 — 简洁、可读，供 LLM prompt 直接拼接。
     */
    private String digestContext(ReminderReplaySample s) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("score=").append(fmt(s.finalScore()));
        sb.append(",evi=").append(fmt(s.evidenceScore()));
        sb.append(",timing=").append(fmt(s.timingScore()));
        sb.append(",urgency=").append(fmt(s.urgencyScore()));
        sb.append(",fit=").append(fmt(s.userFitScore()));
        sb.append(",dupPenalty=").append(fmt(s.duplicatePenalty()));
        sb.append(",todayCnt=").append(s.topicRemindersSentToday());
        sb.append(",actedCnt30=").append(s.topicActedCount30d());
        sb.append(",dismissedCnt30=").append(s.topicDismissedCount30d());
        if (s.acted()) sb.append(",outcome=ACTED");
        else if (s.snoozed()) sb.append(",outcome=SNOOZED");
        else if (s.dismissed()) sb.append(",outcome=DISMISSED");
        else if (s.notRelevant()) sb.append(",outcome=NOT_RELEVANT");
        else if (s.readOnly()) sb.append(",outcome=READ_ONLY");
        return sb.toString();
    }

    private static String fmt(float v) {
        return String.format("%.2f", v);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
