package com.lifepilot.eval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 评估框架配置属性。
 *
 * <p>绑定 {@code lifepilot.eval} 配置前缀。使用 JavaBean 风格以兼容 Spring Boot 配置绑定。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
@ConfigurationProperties(prefix = "lifepilot.eval")
public class EvalConfigProperties {

    /** 评估框架总开关，默认 true。 */
    private boolean enabled = true;

    /** Benchmark 场景 YAML 目录，默认 ${user.home}/.lifepilot/eval/scenarios。 */
    private String scenarioDirectory = "${user.home}/.lifepilot/eval/scenarios";

    /** 默认通过阈值 [0.0, 1.0]，默认 0.7。 */
    private double defaultPassThreshold = 0.7;

    /** 退化检测阈值（与上次运行的平均分差值），默认 0.1。 */
    private double degradationThreshold = 0.1;

    /** LLM Judge 配置。 */
    private LlmJudge llmJudge = new LlmJudge();

    /** 持久化配置。 */
    private Store store = new Store();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getScenarioDirectory() { return scenarioDirectory; }
    public void setScenarioDirectory(String scenarioDirectory) { this.scenarioDirectory = scenarioDirectory; }

    public double getDefaultPassThreshold() { return defaultPassThreshold; }
    public void setDefaultPassThreshold(double defaultPassThreshold) { this.defaultPassThreshold = defaultPassThreshold; }

    public double getDegradationThreshold() { return degradationThreshold; }
    public void setDegradationThreshold(double degradationThreshold) { this.degradationThreshold = degradationThreshold; }

    public LlmJudge getLlmJudge() { return llmJudge; }
    public void setLlmJudge(LlmJudge llmJudge) { this.llmJudge = llmJudge; }

    public Store getStore() { return store; }
    public void setStore(Store store) { this.store = store; }

    /**
     * LLM Judge 配置 — 控制 LLM 语义评估的行为参数。
     *
     * @author zsg
     * @since 2026-08-01
     */
    public static class LlmJudge {

        /** LLM Judge 场景名称，默认 eval-judge。 */
        private String scene = "eval-judge";

        /** LLM 调用超时（秒），默认 30。 */
        private int timeoutSeconds = 30;

        /** 降级默认评分 [0.0, 1.0]，默认 0.5。 */
        private double fallbackScore = 0.5;

        /** 最大重试次数，默认 1。 */
        private int maxRetries = 1;

        public String getScene() { return scene; }
        public void setScene(String scene) { this.scene = scene; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public double getFallbackScore() { return fallbackScore; }
        public void setFallbackScore(double fallbackScore) { this.fallbackScore = fallbackScore; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    /**
     * 持久化配置 — 控制评估结果存储的行为参数。
     *
     * @author zsg
     * @since 2026-08-01
     */
    public static class Store {

        /** 历史查询默认限制，默认 50。 */
        private int defaultQueryLimit = 50;

        public int getDefaultQueryLimit() { return defaultQueryLimit; }
        public void setDefaultQueryLimit(int defaultQueryLimit) { this.defaultQueryLimit = defaultQueryLimit; }
    }
}
