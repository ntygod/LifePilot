package com.lifepilot.document.patch;

/**
 * patch 失败的 op 描述 —— 透传给 LLM 以便重试。
 *
 * @param opIndex    op 在请求数组中的下标
 * @param opType     op 类型（replace_text / insert_paragraph_after / ...）
 * @param reason     失败原因标识（locator_not_found / locator_not_unique / cells_mismatch / ...）
 * @param matchCount 命中次数（定位失败时填 0 或 >1；其他失败填 -1）
 * @param hint       人类可读提示
 * @author zsg
 * @since 2026-04-21
 */
public record FailedOp(int opIndex, String opType, String reason, int matchCount, String hint) {}
