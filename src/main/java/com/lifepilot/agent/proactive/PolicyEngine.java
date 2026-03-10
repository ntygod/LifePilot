package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.candidate.CandidateProvider;
import com.lifepilot.agent.proactive.config.ProactiveConfigProperties;
import com.lifepilot.agent.proactive.model.ProactiveCandidate;
import com.lifepilot.agent.proactive.model.SignalBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

// NotificationTypeRegistry 已移除：PolicyEngine 不直接使用，冷却期查询由 FrequencyStateManager 负责

/**
 * 策略引擎 — 全局策略过滤 + 遍历所有 CandidateProvider 收集候选。
 *
 * <p>评估链路：免打扰前置过滤 → 遍历 CandidateProvider → 类型开关 + 冷却期后置过滤。
 * 目标执行时间 &lt; 10ms。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final List<CandidateProvider> candidateProviders;
    private final FrequencyStateManager frequencyStateManager;
    private final ProactiveConfigProperties config;

    public PolicyEngine(List<CandidateProvider> candidateProviders,
                        FrequencyStateManager frequencyStateManager,
                        ProactiveConfigProperties config) {
        this.candidateProviders = List.copyOf(candidateProviders);
        this.frequencyStateManager = frequencyStateManager;
        this.config = config;
    }

    /**
     * 评估信号包，生成候选提醒列表。
     *
     * @param signals 信号包
     * @return 不可变候选列表
     */
    public List<ProactiveCandidate> evaluate(SignalBundle signals) {
        // 1. 免打扰前置过滤
        if (isInQuietHours(signals.currentTime().toLocalTime())) {
            log.debug("当前处于免打扰时段，跳过所有候选");
            return List.of();
        }

        // 2. 遍历所有 CandidateProvider
        var candidates = new ArrayList<ProactiveCandidate>();
        for (CandidateProvider provider : candidateProviders) {
            try {
                candidates.addAll(provider.evaluate(signals));
            } catch (Exception e) {
                log.warn("候选提供者评估失败: providerId={}, error={}", provider.id(), e.getMessage());
            }
        }

        // 3. 全局后置过滤：类型开关 + 冷却期
        return candidates.stream()
                .filter(c -> config.isTypeEnabled(c.typeId()))
                .filter(c -> !frequencyStateManager.isInCooldown(c.typeId(), c.subjectId()))
                .toList();
    }

    /**
     * 判断当前时间是否在免打扰时段内。
     * 支持跨午夜的时段（如 22:00 ~ 08:00）。
     */
    private boolean isInQuietHours(LocalTime time) {
        var start = LocalTime.of(config.getQuietHoursStart(), 0);
        var end = LocalTime.of(config.getQuietHoursEnd(), 0);

        if (start.isBefore(end)) {
            // 不跨午夜：如 08:00 ~ 22:00
            return !time.isBefore(start) && time.isBefore(end);
        } else {
            // 跨午夜：如 22:00 ~ 08:00
            return !time.isBefore(start) || time.isBefore(end);
        }
    }
}
