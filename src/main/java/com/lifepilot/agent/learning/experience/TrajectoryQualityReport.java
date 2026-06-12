package com.lifepilot.agent.learning.experience;

/**
 * 轨迹质量报告 — 数据质量评估结果。
 *
 * @author zsg
 * @since 2026-03-18
 */
public record TrajectoryQualityReport(
        boolean goalClarity,
        boolean trajectoryCompleteness,
        float toolSuccessRatio,
        boolean taskSuccess,
        int totalSteps,
        boolean qualityPassed
) {}
