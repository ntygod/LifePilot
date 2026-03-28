package com.lifepilot.agent.task.reminder;

/**
 * 提醒文案生成器。
 *
 * <p>负责把算法层的提醒决策转换成最终面向用户的自然语言提醒。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public interface ReminderMessageGenerator {

    /**
     * 生成提醒正文。
     *
     * @param userId   用户 ID
     * @param decision 决策结果
     * @param snapshot 主题快照
     * @param context  运行时上下文
     * @return 提醒文案
     */
    ReminderMessage generate(String userId,
                             ReminderDecision decision,
                             ReminderTopicSnapshot snapshot,
                             ReminderRuntimeContext context);
}
