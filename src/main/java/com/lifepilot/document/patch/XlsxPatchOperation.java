package com.lifepilot.document.patch;

/**
 * xlsx 独占的 patch 操作 —— P3B 新增 4 个 op record 挂在此接口下。
 *
 * <p>{@link com.lifepilot.document.patch.xlsx.XlsxPatchEngine} 按本接口
 * pattern matching，保持 switch 穷尽。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface XlsxPatchOperation extends DocumentPatchOperation
        permits UpdateCellOp, InsertRowOp, DeleteRowOp, SetRangeOp {
}
