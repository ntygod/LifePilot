package com.lifepilot.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆系统配置属性。
 *
 * <p>绑定 {@code lifepilot.memory} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@ConfigurationProperties(prefix = "lifepilot.memory")
public class MemoryProperties {

    /** 记忆系统总开关，默认 true。 */
    private boolean enabled = true;

    /** L1 工作记忆 Token 预算上限，默认 8000。 */
    private int workingMemoryTokenBudget = 8000;

    /** 会话空闲超时（分钟），超时后自动 flush，默认 30。 */
    private int idleSessionTimeoutMinutes = 30;

    /** 触发压缩的 Token 阈值，默认 4000。 */
    private int compressionThresholdTokens = 4000;

    /** 记忆巩固回溯天数，默认 7。 */
    private int consolidationLookbackDays = 7;

    /** 遗忘阈值 [0.0, 1.0]，低于此值的记忆将被遗忘，默认 0.7。 */
    private double forgettingThreshold = 0.7;

    /** 最大保留天数，超过后自动归档，默认 180。 */
    private int maxRetentionDays = 180;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWorkingMemoryTokenBudget() { return workingMemoryTokenBudget; }
    public void setWorkingMemoryTokenBudget(int workingMemoryTokenBudget) { this.workingMemoryTokenBudget = workingMemoryTokenBudget; }

    public int getIdleSessionTimeoutMinutes() { return idleSessionTimeoutMinutes; }
    public void setIdleSessionTimeoutMinutes(int idleSessionTimeoutMinutes) { this.idleSessionTimeoutMinutes = idleSessionTimeoutMinutes; }

    public int getCompressionThresholdTokens() { return compressionThresholdTokens; }
    public void setCompressionThresholdTokens(int compressionThresholdTokens) { this.compressionThresholdTokens = compressionThresholdTokens; }

    public int getConsolidationLookbackDays() { return consolidationLookbackDays; }
    public void setConsolidationLookbackDays(int consolidationLookbackDays) { this.consolidationLookbackDays = consolidationLookbackDays; }

    public double getForgettingThreshold() { return forgettingThreshold; }
    public void setForgettingThreshold(double forgettingThreshold) { this.forgettingThreshold = forgettingThreshold; }

    public int getMaxRetentionDays() { return maxRetentionDays; }
    public void setMaxRetentionDays(int maxRetentionDays) { this.maxRetentionDays = maxRetentionDays; }
}
