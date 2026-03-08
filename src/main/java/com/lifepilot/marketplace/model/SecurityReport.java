package com.lifepilot.marketplace.model;

import java.util.List;

/**
 * 安全扫描报告 — 包含所有发现和整体风险级别。
 *
 * @param findings    安全发现列表
 * @param overallRisk 整体风险级别（取所有发现中的最高级别）
 * @author zsg
 * @since 2026-03-05
 */
public record SecurityReport(
        List<SecurityFinding> findings,
        RiskLevel overallRisk
) {
    public SecurityReport {
        findings = List.copyOf(findings);
    }
}
