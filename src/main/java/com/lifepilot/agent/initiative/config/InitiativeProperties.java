package com.lifepilot.agent.initiative.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 主动引擎配置属性。
 *
 * @author zsg
 * @since 2026-06-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.initiative")
public class InitiativeProperties {

    /** 总开关，默认关闭（Phase 3 完成后开启）。 */
    private boolean enabled = false;

    /** 想法池最大活跃想法数。 */
    private int maxActiveThoughts = 20;

    /** BREWING 状态最长存活时间（小时）。 */
    private int brewingTtlHours = 72;

    /** READY 状态最长等待时间（小时）。 */
    private int readyTtlHours = 48;

    /** 每日最大主动对话数。 */
    private int dailyMaxExpressions = 3;

    /** 两次表达之间的最小间隔（分钟）。 */
    private int minIntervalMinutes = 60;

    /** 静默时段开始（HH:mm）。 */
    private String quietHoursStart = "23:00";

    /** 静默时段结束（HH:mm）。 */
    private String quietHoursEnd = "08:00";

    /** 空闲思考开关。 */
    private boolean idleThinkingEnabled = true;

    /** 空闲检测阈值（分钟）。 */
    private int idleThresholdMinutes = 30;

    /** 每天最多空闲思考几次。 */
    private int idleThinkingMaxDaily = 4;

    /** 成熟度演化模型配置（thought-maturity-evolution）。 */
    private Maturity maturity = new Maturity();

    /**
     * 成熟度演化参数。
     */
    @Setter
    @Getter
    public static class Maturity {
        /** 就绪阈值（≥ 则 BREWING→READY）。 */
        private float readyThreshold = 0.6f;
        /** 降级阈值（READY 且 &lt; 则回退 BREWING；与 readyThreshold 构成迟滞带）。 */
        private float demoteThreshold = 0.5f;
        /** 淘汰下限（≤ 则 DISMISSED）。 */
        private float dismissFloor = 0.15f;
        /** 强化基础增益（再乘证据权重与边际递减系数）。 */
        private float reinforceBaseGain = 0.15f;
        /** 停滞衰减半衰期（小时）。 */
        private double decayHalfLifeHours = 48.0;
        /** 衰减宽限期（小时，期内不衰减）。 */
        private double decayGraceHours = 24.0;
        /** 截止升温窗口（小时，截止前此窗口内 pull 从 0 升至 1）。 */
        private double deadlinePullWindowHours = 72.0;
    }
}
