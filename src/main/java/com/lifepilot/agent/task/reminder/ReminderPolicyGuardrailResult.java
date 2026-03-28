package com.lifepilot.agent.task.reminder;

/**
 * 主动提醒策略护栏结果。
 *
 * <p>表示原始调优结果经过护栏裁剪后的最终生效配置。</p>
 *
 * @param config    最终生效配置
 * @param adjusted  是否发生过护栏调整
 * @param rolledBack 是否回退到了锚点策略
 * @param summary   护栏摘要
 * @author zsg
 * @since 2026-03-29
 */
public record ReminderPolicyGuardrailResult(
        ReminderPolicyConfig config,
        boolean adjusted,
        boolean rolledBack,
        String summary
) {

    public ReminderPolicyGuardrailResult {
        config = config != null ? config : new ReminderPolicyConfig();
        summary = summary != null ? summary : "未触发护栏";
    }

    public static ReminderPolicyGuardrailResult passthrough(ReminderPolicyConfig config) {
        return new ReminderPolicyGuardrailResult(config, false, false, "未触发护栏");
    }
}
