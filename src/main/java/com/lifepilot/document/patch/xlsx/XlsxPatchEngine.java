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
 * <p>Task 5 仅实装 {@code update_cell};其余 3 个 op（insert_row / delete_row / set_range）
 * Task 6 补齐,当前抛 {@link UnsupportedOperationException}。</p>
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

    // ===== 其余 3 op 暂时抛未实现（Task 6 补） =====

    private AppliedXlsxOp applyInsertRow(XSSFWorkbook wb, InsertRowOp op) {
        throw new UnsupportedOperationException("Task 6 补齐");
    }

    private AppliedXlsxOp applyDeleteRow(XSSFWorkbook wb, DeleteRowOp op) {
        throw new UnsupportedOperationException("Task 6 补齐");
    }

    private AppliedXlsxOp applySetRange(XSSFWorkbook wb, SetRangeOp op) {
        throw new UnsupportedOperationException("Task 6 补齐");
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
