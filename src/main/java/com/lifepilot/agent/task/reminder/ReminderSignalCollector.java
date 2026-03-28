package com.lifepilot.agent.task.reminder;

import java.util.List;

/**
 * 提醒信号采集器。
 *
 * <p>负责把记忆、通知和外部事实源转换为算法层可消费的主题快照。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public interface ReminderSignalCollector {

    /**
     * 采集当前用户的提醒主题快照。
     *
     * @param userId  目标用户 ID
     * @param context 当前运行时上下文
     * @return 主题快照列表
     */
    List<ReminderTopicSnapshot> collect(String userId, ReminderRuntimeContext context);
}
