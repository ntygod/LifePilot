package com.lifepilot.agent.intelligence.config;

import com.lifepilot.agent.intelligence.AdaptiveDecisionEngine;
import com.lifepilot.agent.intelligence.CapabilityAssessor;
import com.lifepilot.agent.intelligence.EnvironmentPerceptor;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * 智能层自动装配 — 注册决策引擎、能力评估器、环境感知器。
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration
@EnableConfigurationProperties(IntelligenceProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.intelligence", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class IntelligenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public CapabilityAssessor capabilityAssessor(IntelligenceProperties properties) {
        log.info("智能层: 注册 CapabilityAssessor");
        return new CapabilityAssessor(properties.getToolHealthWindowSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public EnvironmentPerceptor environmentPerceptor(
            CapabilityAssessor capabilityAssessor,
            IntelligenceProperties properties) {
        log.info("智能层: 注册 EnvironmentPerceptor");
        return new EnvironmentPerceptor(capabilityAssessor, properties.getEnvironmentCacheTtlSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(IntentMatcher.class)
    @ConditionalOnProperty(prefix = "lifepilot.intelligence", name = "decision-signal-enabled",
            havingValue = "true", matchIfMissing = true)
    public AdaptiveDecisionEngine adaptiveDecisionEngine(
            CapabilityAssessor capabilityAssessor,
            EnvironmentPerceptor environmentPerceptor,
            IntelligenceProperties properties,
            IntentMatcher intentMatcher) {
        Duration effectiveExperienceMatchTimeout = effectiveExperienceMatchTimeout(properties);
        Duration backgroundTimeout = backgroundExperienceMatchTimeout(properties);
        log.info("智能层: 注册 AdaptiveDecisionEngine, minExperienceConfidence={}, experienceMatchTimeoutMs={}, effectiveExperienceMatchTimeoutMs={}, experienceMatchBackgroundTimeoutMs={}, experienceMatchTrigger={}, experienceMatchMaxGoalChars={}, experienceMatchMaxPending={}, experienceMatchRecentTtlSeconds={}, toolHealthSignalMaxTools={}",
                properties.getMinExperienceConfidence(), properties.getExperienceMatchTimeoutMs(),
                effectiveExperienceMatchTimeout.toMillis(),
                backgroundTimeout.toMillis(),
                properties.getExperienceMatchTrigger(), properties.getExperienceMatchMaxGoalChars(),
                properties.getExperienceMatchMaxPending(),
                properties.getExperienceMatchRecentTtlSeconds(),
                properties.getToolHealthSignalMaxTools());
        return new AdaptiveDecisionEngine(
                capabilityAssessor,
                environmentPerceptor,
                intentMatcher,
                properties.getMinExperienceConfidence(),
                effectiveExperienceMatchTimeout,
                properties.getExperienceMatchTrigger(),
                properties.getExperienceMatchMinGoalChars(),
                properties.getExperienceMatchMaxGoalChars(),
                properties.getExperienceMatchMaxPending(),
                Duration.ofSeconds(properties.getExperienceMatchRecentTtlSeconds()),
                properties.getExperienceMatchRecentMax(),
                backgroundTimeout,
                properties.getToolHealthSignalMaxTools());
    }

    private Duration effectiveExperienceMatchTimeout(IntelligenceProperties properties) {
        long configuredTimeoutMs = properties.getExperienceMatchTimeoutMs();
        if (configuredTimeoutMs < 0) {
            throw new IllegalArgumentException("经验匹配主链路等待时间不能为负");
        }
        long capMs = properties.getExperienceMatchForegroundWaitCapMs();
        if (capMs < 0) {
            throw new IllegalArgumentException("经验匹配主链路等待上限不能为负");
        }
        if (configuredTimeoutMs == 0 || capMs == 0) {
            return Duration.ZERO;
        }
        long effectiveMs = Math.min(configuredTimeoutMs, capMs);
        if (effectiveMs < configuredTimeoutMs) {
            log.warn("智能层: 经验匹配等待时间 {}ms 已被限制为 {}ms，避免阻塞主对话",
                    configuredTimeoutMs, effectiveMs);
        }
        return Duration.ofMillis(effectiveMs);
    }

    private Duration backgroundExperienceMatchTimeout(IntelligenceProperties properties) {
        long timeoutMs = properties.getExperienceMatchBackgroundTimeoutMs();
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("经验匹配后台任务超时时间不能为负");
        }
        return Duration.ofMillis(timeoutMs);
    }
}
