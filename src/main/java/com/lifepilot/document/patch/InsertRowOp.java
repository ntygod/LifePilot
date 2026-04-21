package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 在指定 1-based 行号<b>之前</b>插入一行（原第 {@code beforeRow} 行及以下下移）。
 *
 * <p>values 元素类型规则同 {@link UpdateCellOp#newValue()}。POI {@code sheet.shiftRows}
 * 会自动刷新下方行中对该区域的公式引用（相对地址自动 +1）。新行默认继承上一行的
 * {@link org.apache.poi.ss.usermodel.CellStyle}（POI 默认行为）。</p>
 *
 * @param sheet     工作表名
 * @param beforeRow 1-based 行号，合法 [1, 最大行+1]；等于最大行+1 时等价于追加
 * @param values    要写入的新行值列表（长度自由，从第 0 列起依次写入）
 * @param reason    可选
 * @author zsg
 * @since 2026-04-21
 */
public record InsertRowOp(
        String sheet,
        int beforeRow,
        List<Object> values,
        @Nullable String reason
) implements XlsxPatchOperation {

    public InsertRowOp {
        if (sheet == null || sheet.isBlank()) {
            throw new IllegalArgumentException("InsertRowOp.sheet 不能为空");
        }
        if (beforeRow < 1) {
            throw new IllegalArgumentException("InsertRowOp.beforeRow 必须 ≥ 1，当前 " + beforeRow);
        }
        if (values == null) {
            values = List.of();
        }
    }
}
