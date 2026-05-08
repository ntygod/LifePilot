package com.lifepilot.memory.eval.judge;

import com.lifepilot.memory.eval.loader.BenchmarkQuestion;

/**
 * 答案判定器抽象。
 *
 * <p>根据题目类型选择不同策略。sealed 约束可落地的实现集。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public sealed interface AnswerJudge permits ExactMatchJudge, F1Judge, LlmAsJudge {

    /**
     * 判定器名称，用于报告和日志溯源。
     */
    String name();

    /**
     * 对单题判定。
     *
     * @param prediction  Agent 输出的答案
     * @param groundTruth 标准答案
     * @param question    原题信息（questionType 驱动策略决策）
     * @return 判定结果
     */
    JudgeVerdict judge(String prediction, String groundTruth, BenchmarkQuestion question);
}
