package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 批量写入矩形区域 —— A1 range notation 锚定，2D values 与 range 尺寸必须吻合。
 *
 * <p>区域内若含合并单元格（非 anchor 的内部单元格），engine 拒绝整批。每格按
 * {@link UpdateCellOp#newValue()} 规则写入。</p>
 *
 * @param sheet  工作表名
 * @param range  A1 range（如 "B2:D4"）
 * @param values 2D 值（外层长度 = range 行数，每行长度 = range 列数）
 * @param reason 可选
 * @author zsg
 * @since 2026-04-21
 */
public record SetRangeOp(
        String sheet,
        String range,
        List<List<Object>> values,
        @Nullable String reason
) implements XlsxPatchOperation {

    public SetRangeOp {
        if (sheet == null || sheet.isBlank()) {
            throw new IllegalArgumentException("SetRangeOp.sheet 不能为空");
        }
        if (range == null || range.isBlank()) {
            throw new IllegalArgumentException("SetRangeOp.range 不能为空");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("SetRangeOp.values 不能为空");
        }
    }
}
