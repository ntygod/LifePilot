package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

/**
 * 删除指定 1-based 行号的行（后续行上移）。
 *
 * <p>POI {@code sheet.shiftRows} 自动刷新公式引用。若目标行横跨合并区域，engine 拒绝执行。</p>
 *
 * @param sheet  工作表名
 * @param row    1-based 行号
 * @param reason 可选
 * @author zsg
 * @since 2026-04-21
 */
public record DeleteRowOp(
        String sheet,
        int row,
        @Nullable String reason
) implements XlsxPatchOperation {

    public DeleteRowOp {
        if (sheet == null || sheet.isBlank()) {
            throw new IllegalArgumentException("DeleteRowOp.sheet 不能为空");
        }
        if (row < 1) {
            throw new IllegalArgumentException("DeleteRowOp.row 必须 ≥ 1，当前 " + row);
        }
    }
}
