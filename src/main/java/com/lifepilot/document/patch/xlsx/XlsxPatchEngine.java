package com.lifepilot.document.patch.xlsx;

import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.FailedOp;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.SetRangeOp;
import com.lifepilot.document.patch.UpdateCellOp;
import com.lifepilot.document.patch.XlsxPatchOperation;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * xlsx patch 引擎 —— 纯内存操作 {@link XSSFWorkbook}。
 *
 * <p>职责：对给定 XSSFWorkbook 按顺序应用 {@link XlsxPatchOperation} 列表；
 * 任一 op 定位 / 校验失败立即中止，已改动的 workbook 由调用方丢弃（不写盘即天然回滚）。
 * 成功时返回 {@link AppliedXlsxOp} 列表供 {@link XlsxDiffBuilder} 使用。</p>
 *
 * <p>Task 5 落地 {@code update_cell};Task 6 补齐 {@code insert_row / delete_row / set_range}。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class XlsxPatchEngine {

    private static final Logger log = LoggerFactory.getLogger(XlsxPatchEngine.class);
    private static final DataFormatter FORMATTER = new DataFormatter();

    /** patch 执行结果：成功时 appliedOps 非空;失败时 failedOps 非空。 */
    public record EngineResult(boolean success, List<AppliedXlsxOp> appliedOps, List<FailedOp> failedOps) {}

    public EngineResult apply(XSSFWorkbook wb, List<XlsxPatchOperation> ops) {
        List<AppliedXlsxOp> applied = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            XlsxPatchOperation op = ops.get(i);
            try {
                AppliedXlsxOp result = switch (op) {
                    case UpdateCellOp u -> applyUpdateCell(wb, u);
                    case InsertRowOp r -> applyInsertRow(wb, r);
                    case DeleteRowOp r -> applyDeleteRow(wb, r);
                    case SetRangeOp r -> applySetRange(wb, r);
                };
                applied.add(result);
            } catch (XlsxPatchException e) {
                return new EngineResult(false, List.of(),
                        List.of(new FailedOp(i, opType(op), e.reason, e.matchCount, e.hint)));
            } catch (RuntimeException e) {
                log.warn("xlsx patch op #{} 执行异常：op={}, err={}", i, op.getClass().getSimpleName(), e.getMessage());
                return new EngineResult(false, List.of(),
                        List.of(new FailedOp(i, opType(op), "execution_error", -1, e.getMessage())));
            }
        }
        // P1-8：update_cell 写入公式（= 开头字符串）或改了公式依赖的 cell 值时，其它公式的缓存值会失效。
        // 在所有 op 应用完成后调 evaluateAll 触发 workbook 级公式重算，让前端 DataFormatter 看到新值。
        // 失败 soft fail：重算异常只记 warn，不影响 patch 主流程（下次打开文件 Excel 会自己重算）。
        try {
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
        } catch (RuntimeException e) {
            log.warn("xlsx 公式重算失败，软降级：{}", e.getMessage());
        }
        return new EngineResult(true, applied, List.of());
    }

    // ===== update_cell =====

    private AppliedXlsxOp applyUpdateCell(XSSFWorkbook wb, UpdateCellOp op) {
        Sheet sheet = requireSheet(wb, op.sheet());
        CellReference ref = CellAddressResolver.parseCell(op.cell())
                .orElseThrow(() -> XlsxPatchException.invalidAddress(
                        "invalid_cell_address", "cell 地址格式错误：" + op.cell()));
        checkNotInsideMergedRegion(sheet, ref);

        Row row = sheet.getRow(ref.getRow());
        if (row == null) row = sheet.createRow(ref.getRow());
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) cell = row.createCell(ref.getCol());

        String before = cellToString(cell);
        writeCellValue(cell, op.newValue());
        return new AppliedXlsxOp(op, before, 1, 1);
    }

    /** 把 cell 当前内容转人类可读字符串;空 / 空 blank 返回 ""。 */
    static String cellToString(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.FORMULA) return "=" + cell.getCellFormula();
        return FORMATTER.formatCellValue(cell);
    }

    /** 按 spec §2.2 的类型规则写入 cell;保留 CellStyle（POI setCellValue 默认行为）。 */
    static void writeCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
            return;
        }
        if (value instanceof Boolean b) {
            cell.setCellValue(b);
            return;
        }
        String s = value.toString();
        if (s.startsWith("=")) {
            cell.setCellFormula(s.substring(1));
        } else {
            cell.setCellValue(s);
        }
    }

    /** 若 ref 落在某合并区域内且不是 anchor,抛 cell_inside_merged_region。 */
    static void checkNotInsideMergedRegion(Sheet sheet, CellReference ref) {
        int r = ref.getRow();
        int c = ref.getCol();
        for (CellRangeAddress mr : sheet.getMergedRegions()) {
            if (mr.isInRange(r, c)) {
                if (r == mr.getFirstRow() && c == mr.getFirstColumn()) return;
                String anchorRef = new CellReference(mr.getFirstRow(), mr.getFirstColumn()).formatAsString();
                throw XlsxPatchException.inMerged(
                        "cell_inside_merged_region",
                        "目标单元格在合并区域 " + mr.formatAsString() + " 内,请改用 anchor 地址 " + anchorRef + " 或先解除合并");
            }
        }
    }

    // ===== insert_row =====

    private AppliedXlsxOp applyInsertRow(XSSFWorkbook wb, InsertRowOp op) {
        Sheet sheet = requireSheet(wb, op.sheet());
        int targetIdx = op.beforeRow() - 1;
        int lastRow = sheet.getLastRowNum();

        if (targetIdx < 0 || targetIdx > lastRow + 1) {
            throw XlsxPatchException.invalidRow(
                    "invalid_row_number",
                    "行号 " + op.beforeRow() + " 越界，合法范围 1.." + (lastRow + 2));
        }

        if (targetIdx > lastRow) {
            Row newRow = sheet.createRow(targetIdx);
            fillRow(newRow, op.values());
            return new AppliedXlsxOp(op, "", 1, op.values().size());
        }

        sheet.shiftRows(targetIdx, lastRow, 1);
        Row newRow = sheet.createRow(targetIdx);
        fillRow(newRow, op.values());
        return new AppliedXlsxOp(op, "", 1, op.values().size());
    }

    private static void fillRow(Row row, List<Object> values) {
        for (int c = 0; c < values.size(); c++) {
            Cell cell = row.createCell(c);
            writeCellValue(cell, values.get(c));
        }
    }

    // ===== delete_row =====

    private AppliedXlsxOp applyDeleteRow(XSSFWorkbook wb, DeleteRowOp op) {
        Sheet sheet = requireSheet(wb, op.sheet());
        int idx = op.row() - 1;
        int lastRow = sheet.getLastRowNum();
        if (idx < 0 || idx > lastRow) {
            throw XlsxPatchException.invalidRow(
                    "row_not_found", "行 " + op.row() + " 不存在");
        }
        for (CellRangeAddress mr : sheet.getMergedRegions()) {
            if (mr.getFirstRow() <= idx && idx <= mr.getLastRow()) {
                throw XlsxPatchException.inMerged(
                        "row_in_merged_region",
                        "目标行横跨合并区域 " + mr.formatAsString() + "，无法删除");
            }
        }
        String beforeSnapshot = rowSnapshot(sheet.getRow(idx));

        Row row = sheet.getRow(idx);
        if (row != null) sheet.removeRow(row);
        if (idx < lastRow) {
            sheet.shiftRows(idx + 1, lastRow, -1);
        }
        return new AppliedXlsxOp(op, beforeSnapshot, 1, 0);
    }

    /** 把一行所有 cell 用 "|" 拼成人类可读预览（供 diff 里 delete segment 使用）。 */
    private static String rowSnapshot(Row row) {
        if (row == null) return "(空行)";
        StringBuilder sb = new StringBuilder();
        short last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(cellToString(row.getCell(c)));
        }
        return sb.toString();
    }

    // ===== set_range =====

    private AppliedXlsxOp applySetRange(XSSFWorkbook wb, SetRangeOp op) {
        Sheet sheet = requireSheet(wb, op.sheet());
        CellRangeAddress addr = CellAddressResolver.parseRange(op.range())
                .orElseThrow(() -> XlsxPatchException.invalidAddress(
                        "invalid_range", "range 地址非法：" + op.range()));
        int rows = addr.getLastRow() - addr.getFirstRow() + 1;
        int cols = addr.getLastColumn() - addr.getFirstColumn() + 1;

        if (op.values().size() != rows) {
            throw XlsxPatchException.rangeMismatch(
                    "range_size_mismatch",
                    "values 外层长度 " + op.values().size() + " 与 range 行数 " + rows + " 不符");
        }
        for (int r = 0; r < rows; r++) {
            if (op.values().get(r).size() != cols) {
                throw XlsxPatchException.rangeMismatch(
                        "range_size_mismatch",
                        "values[" + r + "] 长度 " + op.values().get(r).size()
                                + " 与 range 列数 " + cols + " 不符");
            }
        }
        List<String> clashing = new ArrayList<>();
        for (CellRangeAddress mr : sheet.getMergedRegions()) {
            if (mr.intersects(addr)) clashing.add(mr.formatAsString());
        }
        if (!clashing.isEmpty()) {
            throw XlsxPatchException.inMerged(
                    "range_contains_merged_region",
                    "range " + addr.formatAsString() + " 包含合并区域 "
                            + String.join(", ", clashing) + "，请拆分操作");
        }

        for (int r = 0; r < rows; r++) {
            int absRow = addr.getFirstRow() + r;
            Row row = sheet.getRow(absRow);
            if (row == null) row = sheet.createRow(absRow);
            for (int c = 0; c < cols; c++) {
                int absCol = addr.getFirstColumn() + c;
                Cell cell = row.getCell(absCol);
                if (cell == null) cell = row.createCell(absCol);
                writeCellValue(cell, op.values().get(r).get(c));
            }
        }
        return new AppliedXlsxOp(op, rows + "x" + cols + " 批量（预览略）", rows, cols);
    }

    // ===== 辅助 =====

    static Sheet requireSheet(XSSFWorkbook wb, String name) {
        Sheet s = wb.getSheet(name);
        if (s == null) {
            throw XlsxPatchException.notFound(
                    "sheet_not_found",
                    "工作表 " + name + " 不存在（区分大小写）");
        }
        return s;
    }

    static String opType(XlsxPatchOperation op) {
        return switch (op) {
            case UpdateCellOp u -> "update_cell";
            case InsertRowOp r -> "insert_row";
            case DeleteRowOp r -> "delete_row";
            case SetRangeOp r -> "set_range";
        };
    }

    /** 内部受检异常,apply 捕获转成 FailedOp。 */
    static final class XlsxPatchException extends RuntimeException {
        final String reason;
        final int matchCount;
        final String hint;

        private XlsxPatchException(String reason, int matchCount, String hint) {
            super(reason + ": " + hint);
            this.reason = reason;
            this.matchCount = matchCount;
            this.hint = hint;
        }

        static XlsxPatchException notFound(String reason, String hint) {
            return new XlsxPatchException(reason, 0, hint);
        }

        static XlsxPatchException invalidAddress(String reason, String hint) {
            return new XlsxPatchException(reason, -1, hint);
        }

        static XlsxPatchException inMerged(String reason, String hint) {
            return new XlsxPatchException(reason, -1, hint);
        }

        static XlsxPatchException invalidRow(String reason, String hint) {
            return new XlsxPatchException(reason, -1, hint);
        }

        static XlsxPatchException rangeMismatch(String reason, String hint) {
            return new XlsxPatchException(reason, -1, hint);
        }
    }
}
