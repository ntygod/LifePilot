package com.lifepilot.skill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skill 系统配置属性。
 *
 * <p>绑定 {@code lifepilot.skills} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@ConfigurationProperties(prefix = "lifepilot.skills")
public class SkillConfigProperties {

    /** Skill 系统总开关，默认 true。 */
    private boolean enabled = true;

    /** 最大并发激活数，默认 5。 */
    private int maxConcurrentActivations = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxConcurrentActivations() { return maxConcurrentActivations; }
    public void setMaxConcurrentActivations(int maxConcurrentActivations) { this.maxConcurrentActivations = maxConcurrentActivations; }
}
