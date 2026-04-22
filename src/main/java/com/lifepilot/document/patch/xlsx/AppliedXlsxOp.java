package com.lifepilot.document.patch.xlsx;

import com.lifepilot.document.patch.XlsxPatchOperation;

/**
 * 已成功应用的 xlsx op —— 带 before snapshot 供 {@link XlsxDiffBuilder} 生成 diff segments。
 *
 * <p>字段含义按 op 类型取舍：</p>
 * <ul>
 *   <li>{@code update_cell}：{@code beforeSnapshot} = 原 cell 字符串表示；{@code rowsAffected} / {@code colsAffected} 为 1</li>
 *   <li>{@code insert_row}：{@code beforeSnapshot} 无意义（传 {@code ""}）；{@code rowsAffected} = 1</li>
 *   <li>{@code delete_row}：{@code beforeSnapshot} = 被删整行的 "cellA | cellB | ..." 预览</li>
 *   <li>{@code set_range}：{@code beforeSnapshot} = "rowsXcols 批量（预览略）"；{@code rowsAffected} / {@code colsAffected} 反映 range 尺寸</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-21
 */
public record AppliedXlsxOp(
        XlsxPatchOperation op,
        String beforeSnapshot,
        int rowsAffected,
        int colsAffected
) {}
