package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 表格追加行 —— 按 tableAnchorText 定位表格（表格内任一单元格唯一命中），
 * position=start 插到表格头部，end 追加到尾部。cells 长度必须等于表格列数。
 *
 * @author zsg
 * @since 2026-04-21
 */
public record AddTableRowOp(
        String tableAnchorText,
        String position,
        List<String> cells,
        @Nullable String reason
) implements DocxPatchOperation {

    public static final String POSITION_START = "start";
    public static final String POSITION_END = "end";

    public AddTableRowOp {
        if (tableAnchorText == null || tableAnchorText.isBlank()) {
            throw new IllegalArgumentException("tableAnchorText 不能为空");
        }
        if (position == null || (!position.equals(POSITION_START) && !position.equals(POSITION_END))) {
            throw new IllegalArgumentException("position 必须是 start 或 end");
        }
        if (cells == null || cells.isEmpty()) {
            throw new IllegalArgumentException("cells 不能为空");
        }
        cells = List.copyOf(cells);
    }
}
