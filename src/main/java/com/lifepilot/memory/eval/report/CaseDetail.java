package com.lifepilot.memory.eval.report;

import com.lifepilot.memory.eval.judge.JudgeVerdict;

import java.util.List;

/**
 * 单题评估明细。
 *
 * @param caseId     benchmark case ID（一个 case 可能含多题）
 * @param questionId 题目 ID
 * @param query      问题文本
 * @param prediction Agent 回答
 * @param groundTruth 标准答案
 * @param verdicts   该题所有 Judge 的判定结果（ExactMatch / F1 / LlmAsJudge 可并存）
 * @param latencyMs  召回耗时（毫秒）
 * @param tokens     本题召回拼入 prompt 的 token 数
 * @author zsg
 * @since 2026-05-09
 */
public record CaseDetail(
        String caseId,
        String questionId,
        String query,
        String prediction,
        String groundTruth,
        List<JudgeVerdict> verdicts,
        long latencyMs,
        int tokens
) {
    public CaseDetail {
        if (caseId == null) caseId = "";
        if (questionId == null) questionId = "";
        verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
    }
}
