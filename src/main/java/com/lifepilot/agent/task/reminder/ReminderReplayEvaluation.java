package com.lifepilot.agent.task.reminder;

/**
 * 单条提醒回放结果。
 *
 * @param decisionId                决策 ID
 * @param candidateType             候选类型
 * @param historicalAction          历史最终动作
 * @param replayedAction            回放后的动作
 * @param historicalObservedReward  历史真实奖励
 * @param historicalEstimatedReward 历史动作估计收益
 * @param replayedEstimatedReward   回放动作估计收益
 * @param actionShifted             是否发生动作漂移
 * @param usedBanditEstimate        是否使用了 bandit 估计收益
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderReplayEvaluation(
        String decisionId,
        ReminderCandidateType candidateType,
        ReminderAction historicalAction,
        ReminderAction replayedAction,
        float historicalObservedReward,
        float historicalEstimatedReward,
        float replayedEstimatedReward,
        boolean actionShifted,
        boolean usedBanditEstimate
) {
}
