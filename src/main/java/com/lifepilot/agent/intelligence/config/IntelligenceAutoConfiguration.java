package com.lifepilot.agent.intelligence.config;

import com.lifepilot.agent.intelligence.AdaptiveDecisionEngine;
import com.lifepilot.agent.intelligence.CapabilityAssessor;
import com.lifepilot.agent.intelligence.EnvironmentPerceptor;
import com.lifepilot.memory.procedural.IntentMatcher;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
    public CapabilityAssessor capabilityAssessor() {
        log.info("智能层: 注册 CapabilityAssessor");
        return new CapabilityAssessor();
    }

    @Bean
    @ConditionalOnMissingBean
    public EnvironmentPerceptor environmentPerceptor(CapabilityAssessor capabilityAssessor) {
        log.info("智能层: 注册 EnvironmentPerceptor");
        return new EnvironmentPerceptor(capabilityAssessor);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdaptiveDecisionEngine adaptiveDecisionEngine(
            CapabilityAssessor capabilityAssessor,
            EnvironmentPerceptor environmentPerceptor,
            @Nullable IntentMatcher intentMatcher) {
        log.info("智能层: 注册 AdaptiveDecisionEngine, intentMatcher={}",
                intentMatcher != null ? "available" : "unavailable");
        return new AdaptiveDecisionEngine(capabilityAssessor, environmentPerceptor, intentMatcher);
    }
}
