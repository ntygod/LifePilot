package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

/**
 * 单元格值替换 —— P3B 主力 op。
 *
 * <p>定位：{@code sheet}（精确匹配，区分大小写）+ {@code cell}（A1 notation，大小写不敏感）。
 * {@code newValue} 为多态对象，保留 JSON 原始类型：</p>
 * <ul>
 *   <li>{@link Number} → numeric cell</li>
 *   <li>{@link Boolean} → boolean cell</li>
 *   <li>{@link String} 以 {@code =} 开头 → 公式（剥掉前缀后送 POI setCellFormula）</li>
 *   <li>{@link String} 其它 → 字符串字面量</li>
 *   <li>{@code null} → 清空 cell（setBlank）</li>
 * </ul>
 *
 * @param sheet    工作表名（精确匹配）
 * @param cell     单元格地址（A1 notation，如 "B5" / "AA12"）
 * @param newValue 新值（多态）
 * @param reason   可选，LLM 给用户看的解释
 * @author zsg
 * @since 2026-04-21
 */
public record UpdateCellOp(
        String sheet,
        String cell,
        @Nullable Object newValue,
        @Nullable String reason
) implements XlsxPatchOperation {

    public UpdateCellOp {
        if (sheet == null || sheet.isBlank()) {
            throw new IllegalArgumentException("UpdateCellOp.sheet 不能为空");
        }
        if (cell == null || cell.isBlank()) {
            throw new IllegalArgumentException("UpdateCellOp.cell 不能为空");
        }
    }
}
