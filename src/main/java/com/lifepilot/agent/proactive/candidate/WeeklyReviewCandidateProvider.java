package com.lifepilot.agent.proactive.candidate;

import com.lifepilot.agent.proactive.config.ProactiveConfigProperties;
import com.lifepilot.agent.proactive.model.InitiativeType;
import com.lifepilot.agent.proactive.model.ProactiveCandidate;
import com.lifepilot.agent.proactive.model.SignalBundle;
import com.lifepilot.agent.proactive.model.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 每周回顾候选提供者 — 基于时间信号判断是否触发每周回顾。
 *
 * <p>当当前星期等于配置的 {@code weeklyReviewDay}（默认周日=7）
 * 且当前小时等于 {@code weeklyReviewHour}（默认 10 点）时，
 * 产生 {@code weekly_review} 候选。不依赖特定 SignalSource，
 * 直接使用 SignalBundle 中的时间信号。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class WeeklyReviewCandidateProvider implements CandidateProvider {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReviewCandidateProvider.class);

    private final ProactiveConfigProperties config;

    public WeeklyReviewCandidateProvider(ProactiveConfigProperties config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "weekly-review-candidate";
    }

    @Override
    public List<ProactiveCandidate> evaluate(SignalBundle signals) {
        // weeklyReviewDay: 1=Monday ... 7=Sunday, DayOfWeek.getValue() uses same convention
        int currentDayValue = signals.dayOfWeek().getValue();
        int currentHour = signals.currentTime().getHour();

        if (currentDayValue == config.getWeeklyReviewDay() && currentHour == config.getWeeklyReviewHour()) {
            log.debug("每周回顾触发条件满足: dayOfWeek={}, currentHour={}", signals.dayOfWeek(), currentHour);
            return List.of(new ProactiveCandidate(
                    "weekly_review",
                    Urgency.LOW,
                    "本周活动回顾",
                    null,
                    InitiativeType.PASSIVE_HINT));
        }
        return List.of();
    }
}
