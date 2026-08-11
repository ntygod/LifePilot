package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 本地产品诊断报告。
 *
 * @param generatedAt 生成时间
 * @param status      汇总状态：OK / WARN / ERROR
 * @param summary     一句话摘要
 * @param app         应用信息
 * @param runtime     Java / OS / 路径等运行时信息
 * @param counts      关键资源计数
 * @param checks      检查项列表
 * @param hints       可操作建议
 * @author zsg
 * @since 2026-07-04
 */
public record DiagnosticReportInfo(
        Instant generatedAt,
        String status,
        String summary,
        Map<String, Object> app,
        Map<String, Object> runtime,
        Map<String, Object> counts,
        List<DiagnosticCheckInfo> checks,
        List<String> hints
) {
}
