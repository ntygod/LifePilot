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
 * 每日总结候选提供者 — 基于时间信号判断是否触发每日总结。
 *
 * <p>当当前小时等于配置的 {@code dailySummaryHour}（默认 21 点）时，
 * 产生 {@code daily_summary} 候选。不依赖特定 SignalSource，
 * 直接使用 SignalBundle 中的时间信号。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class DailySummaryCandidateProvider implements CandidateProvider {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryCandidateProvider.class);

    private final ProactiveConfigProperties config;

    public DailySummaryCandidateProvider(ProactiveConfigProperties config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "daily-summary-candidate";
    }

    @Override
    public List<ProactiveCandidate> evaluate(SignalBundle signals) {
        int currentHour = signals.currentTime().getHour();
        if (currentHour == config.getDailySummaryHour()) {
            log.debug("每日总结触发条件满足: currentHour={}", currentHour);
            return List.of(new ProactiveCandidate(
                    "daily_summary",
                    Urgency.LOW,
                    "今日活动总结",
                    null,
                    InitiativeType.PASSIVE_HINT));
        }
        return List.of();
    }
}
