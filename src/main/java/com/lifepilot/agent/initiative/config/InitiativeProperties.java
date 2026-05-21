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
}
