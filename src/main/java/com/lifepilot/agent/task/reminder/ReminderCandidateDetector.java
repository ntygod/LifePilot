package com.lifepilot.agent.task.reminder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 提醒候选检测器。
 *
 * <p>负责基于主题快照识别候选提醒类型，
 * 由规则化检测器控制候选召回范围，再交给统一评分模型排序。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderCandidateDetector {

    private final ReminderScoringModel scoringModel;

    public ReminderCandidateDetector() {
        this(new ReminderScoringModel());
    }

    public ReminderCandidateDetector(ReminderScoringModel scoringModel) {
        this.scoringModel = scoringModel;
    }

    /**
     * 检测并返回候选提醒，按最终分降序。
     */
    public List<ReminderCandidate> detect(ReminderTopicSnapshot snapshot,
                                          ReminderRuntimeContext context,
                                          ReminderPolicyConfig config) {
        List<ReminderCandidate> candidates = new ArrayList<>();
        for (ReminderSignal signal : snapshot.signals()) {
            if (signal.resolved()) {
                continue;
            }
            detectFromSignal(snapshot, signal, context, config).ifPresent(candidates::add);
        }
        return candidates.stream()
                .sorted(Comparator.comparing(ReminderCandidate::finalScore).reversed())
                .toList();
    }

    private Optional<ReminderCandidate> detectFromSignal(ReminderTopicSnapshot snapshot,
                                                         ReminderSignal signal,
                                                         ReminderRuntimeContext context,
                                                         ReminderPolicyConfig config) {
        return switch (signal.kind()) {
            case DEADLINE -> detectDueSoon(snapshot, signal, context, config);
            case COMMITMENT -> detectCommitmentGap(snapshot, signal, context, config);
            case HABIT -> detectHabitWindow(snapshot, signal, context, config);
            case EVENT -> detectPreparationWindow(snapshot, signal, context, config);
            case ANOMALY -> detectBehaviorAnomaly(snapshot, signal, context, config);
        };
    }

    private Optional<ReminderCandidate> detectDueSoon(ReminderTopicSnapshot snapshot,
                                                      ReminderSignal signal,
                                                      ReminderRuntimeContext context,
                                                      ReminderPolicyConfig config) {
        if (signal.relevantAt() == null) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(context.now(), signal.relevantAt());
        if (remaining.toHours() > config.dueSoonThresholdHours()) {
            return Optional.empty();
        }
        return Optional.of(scoringModel.score(
                snapshot, signal, ReminderCandidateType.DUE_SOON, context.now(), context, config,
                "截止时间临近，适合主动提醒"
        ));
    }

    private Optional<ReminderCandidate> detectCommitmentGap(ReminderTopicSnapshot snapshot,
                                                            ReminderSignal signal,
                                                            ReminderRuntimeContext context,
                                                            ReminderPolicyConfig config) {
        Duration elapsed = Duration.between(signal.observedAt(), context.now());
        if (elapsed.toHours() < config.commitmentGapThresholdHours() || signal.evidenceCount() < 2) {
            return Optional.empty();
        }
        return Optional.of(scoringModel.score(
                snapshot, signal, ReminderCandidateType.COMMITMENT_GAP, context.now(), context, config,
                "近期承诺尚未闭环，适合提醒推进"
        ));
    }

    private Optional<ReminderCandidate> detectHabitWindow(ReminderTopicSnapshot snapshot,
                                                          ReminderSignal signal,
                                                          ReminderRuntimeContext context,
                                                          ReminderPolicyConfig config) {
        if (signal.preferredWindowStartHour() == null || signal.preferredWindowEndHour() == null) {
            return Optional.empty();
        }
        Instant nextWindowStart = nextWindowStart(context.now(), context.zoneId(),
                signal.preferredWindowStartHour(), signal.preferredWindowEndHour());
        Duration untilWindow = Duration.between(context.now(), nextWindowStart);
        boolean inWindow = untilWindow.isZero() || untilWindow.isNegative();
        if (!inWindow && untilWindow.compareTo(config.preferredWindowLookahead()) > 0) {
            return Optional.empty();
        }
        Instant suggestedAt = inWindow ? context.now() : nextWindowStart;
        return Optional.of(scoringModel.score(
                snapshot, signal, ReminderCandidateType.HABIT_WINDOW, suggestedAt, context, config,
                "已接近用户的稳定习惯窗口"
        ));
    }

    private Optional<ReminderCandidate> detectPreparationWindow(ReminderTopicSnapshot snapshot,
                                                                ReminderSignal signal,
                                                                ReminderRuntimeContext context,
                                                                ReminderPolicyConfig config) {
        if (signal.relevantAt() == null) {
            return Optional.empty();
        }
        Duration lead = signal.preparationLeadTime() != null
                ? signal.preparationLeadTime()
                : Duration.ofHours(2);
        Instant windowStart = signal.relevantAt().minus(lead);
        if (context.now().isBefore(windowStart)) {
            Duration untilWindow = Duration.between(context.now(), windowStart);
            if (untilWindow.compareTo(config.preferredWindowLookahead()) > 0) {
                return Optional.empty();
            }
            return Optional.of(scoringModel.score(
                    snapshot, signal, ReminderCandidateType.PREPARATION_WINDOW,
                    windowStart, context, config,
                    "事件准备窗口即将开始，适合延后到窗口提醒"
            ));
        }
        if (context.now().isAfter(signal.relevantAt())) {
            return Optional.empty();
        }
        return Optional.of(scoringModel.score(
                snapshot, signal, ReminderCandidateType.PREPARATION_WINDOW,
                context.now(), context, config,
                "已进入事件准备窗口，适合主动提醒"
        ));
    }

    private Optional<ReminderCandidate> detectBehaviorAnomaly(ReminderTopicSnapshot snapshot,
                                                              ReminderSignal signal,
                                                              ReminderRuntimeContext context,
                                                              ReminderPolicyConfig config) {
        if (signal.anomalyScore() < config.anomalyThreshold()) {
            return Optional.empty();
        }
        return Optional.of(scoringModel.score(
                snapshot, signal, ReminderCandidateType.BEHAVIOR_ANOMALY, context.now(), context, config,
                "近期行为偏离稳定模式，适合轻量提醒"
        ));
    }

    private Instant nextWindowStart(Instant now,
                                    ZoneId zoneId,
                                    int startHour,
                                    int endHour) {
        LocalDateTime localDateTime = LocalDateTime.ofInstant(now, zoneId);
        LocalDate today = localDateTime.toLocalDate();
        LocalTime current = localDateTime.toLocalTime();
        LocalTime start = LocalTime.of(startHour, 0);
        LocalTime end = LocalTime.of(endHour, 0);

        if (isWithinWindow(current, start, end)) {
            return now;
        }
        if (start.isBefore(end)) {
            LocalDate targetDate = current.isBefore(start) ? today : today.plusDays(1);
            return LocalDateTime.of(targetDate, start).atZone(zoneId).toInstant();
        }
        if (current.isBefore(start) && !current.isBefore(LocalTime.MIDNIGHT)) {
            return LocalDateTime.of(today, start).atZone(zoneId).toInstant();
        }
        return LocalDateTime.of(today.plusDays(1), start).atZone(zoneId).toInstant();
    }

    private boolean isWithinWindow(LocalTime current, LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !current.isBefore(start) && current.isBefore(end);
        }
        return !current.isBefore(start) || current.isBefore(end);
    }
}
