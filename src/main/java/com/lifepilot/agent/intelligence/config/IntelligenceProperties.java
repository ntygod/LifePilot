package com.lifepilot.agent.intelligence.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能层配置属性。
 *
 * @author zsg
 * @since 2026-06-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.intelligence")
public class IntelligenceProperties {

    /** 智能层总开关，默认 true。 */
    private boolean enabled = true;

    /** 决策信号注入开关，默认 true。 */
    private boolean decisionSignalEnabled = true;

    /** 经验匹配最低置信度，低于此值不注入决策信号，默认 0.6。 */
    private float minExperienceConfidence = 0.6f;

    /** 经验匹配主链路等待时间（毫秒），0 表示只做后台增强不阻塞主对话，默认 0。 */
    private long experienceMatchTimeoutMs = 0;

    /** 经验匹配主链路最大等待上限（毫秒），避免误配置让意图增强拖慢对话，默认 80。 */
    private long experienceMatchForegroundWaitCapMs = 80;

    /** 经验匹配后台任务最大等待时间（毫秒），超时后放弃本轮结果并尝试中断慢任务，默认 1200。 */
    private long experienceMatchBackgroundTimeoutMs = 1200;

    /** 经验匹配触发策略，默认只对明显任务型输入后台增强。 */
    private ExperienceMatchTrigger experienceMatchTrigger = ExperienceMatchTrigger.TASK_LIKE;

    /** 触发经验匹配所需的最少有效字符数，短输入直接跳过，默认 6。 */
    private int experienceMatchMinGoalChars = 6;

    /** 送入经验匹配器的最大字符数，超长输入裁剪为头尾意图探针，默认 240。 */
    private int experienceMatchMaxGoalChars = 240;

    /** 经验匹配后台最大并发任务数，超过后直接跳过本轮增强，默认 1。 */
    private int experienceMatchMaxPending = 1;

    /** 后台命中经验可被后续相关输入复用的时间窗口（秒），默认 300。 */
    private int experienceMatchRecentTtlSeconds = 300;

    /** 最近经验命中缓存数量，默认 8。 */
    private int experienceMatchRecentMax = 8;

    /** 环境感知缓存时间（秒），默认 60。 */
    private int environmentCacheTtlSeconds = 60;

    /** 工具健康滑动窗口大小，默认 20。 */
    private int toolHealthWindowSize = 20;

    /** 决策信号最多扫描的工具数量；超过后跳过工具健康提示，默认 32，0 表示关闭。 */
    private int toolHealthSignalMaxTools = 32;

    /**
     * 经验匹配触发策略。
     *
     * @author zsg
     * @since 2026-07-05
     */
    public enum ExperienceMatchTrigger {
        /** 所有达到长度阈值的输入都尝试后台匹配。 */
        ALWAYS,
        /** 只对明显任务型输入尝试后台匹配。 */
        TASK_LIKE,
        /** 完全关闭经验匹配，只保留工具健康与环境信号。 */
        DISABLED
    }
}
