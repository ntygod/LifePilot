package com.lifepilot.agent.task.proactive;

/**
 * 三级需求检测级别。
 *
 * <p>SILENT: 无变化，跳过（~80%）。
 * FAST: 有变化但无高分候选，轻量响应（~15%）。
 * FULL: 高分候选进入完整推理（~5%）。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public enum DetectionLevel {
    SILENT,
    FAST,
    FULL
}
