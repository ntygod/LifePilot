package com.lifepilot.document.patch.docx;

import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.FailedOp;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
import com.lifepilot.document.patch.NewParagraph;
import com.lifepilot.document.patch.ReplaceTextOp;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * docx patch 引擎 —— 纯内存操作 XWPFDocument。
 *
 * <p>职责：对给定 XWPFDocument 按顺序应用 ops。任一 op 定位失败立即中止，
 * 已改动的 XWPFDocument 由调用方丢弃（不写盘即天然回滚）。成功时返回已应用 op 列表供
 * DiffBuilder 使用。</p>
 *
 * <p>已覆盖全部 4 种 op：replace_text / insert_paragraph_after / delete_paragraph / add_table_row。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class DocxPatchEngine {

    private static final Logger log = LoggerFactory.getLogger(DocxPatchEngine.class);

    private final TextAnchorLocator locator;

    public DocxPatchEngine(TextAnchorLocator locator) {
        this.locator = locator;
    }

    /** patch 执行结果：成功时 appliedOps 非空；失败时 failedOps 非空。 */
    public record EngineResult(boolean success, List<AppliedOp> appliedOps, List<FailedOp> failedOps) {}

    public EngineResult apply(XWPFDocument document, List<DocumentPatchOperation> ops) {
        List<AppliedOp> applied = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            DocumentPatchOperation op = ops.get(i);
            try {
                AppliedOp result = switch (op) {
                    case ReplaceTextOp r -> applyReplaceText(document, r);
                    case InsertParagraphAfterOp r -> applyInsertAfter(document, r);
                    case DeleteParagraphOp r -> applyDeleteParagraph(document, r);
                    case AddTableRowOp r -> applyAddTableRow(document, r);
                };
                applied.add(result);
            } catch (PatchLocatorException e) {
                return new EngineResult(false, List.of(),
                        List.of(new FailedOp(i, opType(op), e.reason, e.matchCount, e.hint)));
            } catch (RuntimeException e) {
                log.warn("patch op #{} 执行异常：op={}, err={}", i, op.getClass().getSimpleName(), e.getMessage());
                return new EngineResult(false, List.of(),
                        List.of(new FailedOp(i, opType(op), "execution_error", -1, e.getMessage())));
            }
        }
        return new EngineResult(true, applied, List.of());
    }

    // ===== replace_text =====

    private AppliedOp applyReplaceText(XWPFDocument doc, ReplaceTextOp op) {
        Optional<ParagraphRunRange> rangeOpt = locator.locate(
                doc, op.beforeContext(), op.target(), op.afterContext());
        if (rangeOpt.isEmpty()) {
            throw PatchLocatorException.notUniqueOrMissing(
                    "locator_not_unique_or_missing",
                    "before_context+target+after_context 在文档中未唯一出现，建议加长锚点");
        }
        ParagraphRunRange range = rangeOpt.get();
        XWPFParagraph para = doc.getParagraphs().get(range.paragraphIndex());
        replaceTextInRange(para, range, op.newText());
        return new AppliedOp(op, range);
    }

    /**
     * 在 [range.startRun, range.endRun] 范围内替换文本：
     * - 首 run：保留其前缀（到 startCharOffset），拼接 newText
     * - 中间 run：清空文本
     * - 尾 run：保留其后缀（从 endCharOffset 开始）；若 startRun == endRun，尾缀拼在首 run 末尾
     *
     * 样式保留策略：首 run 的 font/bold/color/size 自动传承（因为首 run 的文本未完全清除，只是替换中段）。
     */
    private void replaceTextInRange(XWPFParagraph para, ParagraphRunRange range, String newText) {
        List<XWPFRun> runs = para.getRuns();
        XWPFRun startRun = runs.get(range.startRunIndex());
        String startOriginal = getRunText(startRun);

        if (range.startRunIndex() == range.endRunIndex()) {
            String replaced = startOriginal.substring(0, range.startCharOffset())
                    + newText
                    + startOriginal.substring(range.endCharOffset());
            startRun.setText(replaced, 0);
            return;
        }

        XWPFRun endRun = runs.get(range.endRunIndex());
        String endOriginal = getRunText(endRun);

        // 首 run：前缀 + 新文本
        startRun.setText(startOriginal.substring(0, range.startCharOffset()) + newText, 0);
        // 中间 run：清空
        for (int i = range.startRunIndex() + 1; i < range.endRunIndex(); i++) {
            runs.get(i).setText("", 0);
        }
        // 尾 run：保留后缀
        endRun.setText(endOriginal.substring(range.endCharOffset()), 0);
    }

    private String getRunText(XWPFRun run) {
        String t = run.getText(0);
        return t == null ? "" : t;
    }

    // ===== insert_paragraph_after =====

    private AppliedOp applyInsertAfter(XWPFDocument doc, InsertParagraphAfterOp op) {
        int anchorIdx = locator.locateParagraphIndexByFullText(doc, op.anchorParagraphText());
        if (anchorIdx < 0) {
            throw PatchLocatorException.notUniqueOrMissing(
                    "anchor_paragraph_not_unique_or_missing",
                    "anchor_paragraph_text 在文档中未唯一匹配一个段落");
        }
        XWPFParagraph anchor = doc.getParagraphs().get(anchorIdx);
        // POI XWPF 没有直接"在指定位置插入段落"的 high-level API，
        // 但可以通过 org.apache.xmlbeans CTP 的 XmlCursor 在 anchor 的 XML 后插入新段落。
        for (int i = op.newParagraphs().size() - 1; i >= 0; i--) {
            NewParagraph np = op.newParagraphs().get(i);
            org.apache.xmlbeans.XmlCursor cursor = anchor.getCTP().newCursor();
            cursor.toEndToken();
            cursor.toNextToken();
            XWPFParagraph created = doc.insertNewParagraph(cursor);
            applyStyle(created, np.style());
            XWPFRun run = created.createRun();
            run.setText(np.text());
            cursor.dispose();
        }
        return new AppliedOp(op, new ParagraphRunRange(anchorIdx, -1, -1, -1, -1));
    }

    /** 给段落套 styleId。缺失或系统不认识时退回不设（段落保持默认样式）。 */
    private void applyStyle(XWPFParagraph para, String style) {
        if (style == null || style.isBlank() || NewParagraph.STYLE_NORMAL.equals(style)) return;
        try {
            para.setStyle(style);
        } catch (RuntimeException e) {
            // 样式不存在等异常 — 安全退回
        }
    }

    // ===== delete_paragraph =====

    private AppliedOp applyDeleteParagraph(XWPFDocument doc, DeleteParagraphOp op) {
        int idx = locator.locateParagraphIndexByFullText(doc, op.paragraphText());
        if (idx < 0) {
            throw PatchLocatorException.notUniqueOrMissing(
                    "paragraph_text_not_unique_or_missing",
                    "paragraph_text 在文档中未唯一匹配一个段落");
        }
        // XWPFDocument.removeBodyElement(pos) 按 body element 下标删除；段落/表格混合时需要用整体下标
        int bodyPos = doc.getPosOfParagraph(doc.getParagraphs().get(idx));
        doc.removeBodyElement(bodyPos);
        return new AppliedOp(op, new ParagraphRunRange(idx, -1, -1, -1, -1));
    }

    // ===== add_table_row =====

    private AppliedOp applyAddTableRow(XWPFDocument doc, AddTableRowOp op) {
        org.apache.poi.xwpf.usermodel.XWPFTable targetTable = findTableByAnchor(doc, op.tableAnchorText());
        if (targetTable == null) {
            throw PatchLocatorException.notUniqueOrMissing(
                    "table_anchor_not_unique_or_missing",
                    "table_anchor_text 在任何表格单元格中未唯一匹配");
        }
        int cols = targetTable.getRow(0).getTableCells().size();
        if (op.cells().size() != cols) {
            throw PatchLocatorException.cellsMismatch(cols, op.cells().size());
        }
        org.apache.poi.xwpf.usermodel.XWPFTableRow newRow;
        if (AddTableRowOp.POSITION_START.equals(op.position())) {
            // 在第 0 行前插入 — 需用 insertNewTableRow(0)
            org.apache.xmlbeans.XmlCursor cursor = targetTable.getRow(0).getCtRow().newCursor();
            newRow = targetTable.insertNewTableRow(0);
            cursor.dispose();
            fillRowCells(newRow, op.cells(), cols);
        } else {
            newRow = targetTable.createRow();
            fillRowCells(newRow, op.cells(), cols);
        }
        return new AppliedOp(op, null);
    }

    /** 按 anchorText 在文档所有表格单元格里唯一查找表格。*/
    private org.apache.poi.xwpf.usermodel.XWPFTable findTableByAnchor(XWPFDocument doc, String anchorText) {
        org.apache.poi.xwpf.usermodel.XWPFTable hit = null;
        for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
            for (var row : table.getRows()) {
                for (var cell : row.getTableCells()) {
                    if (cell.getText().contains(anchorText)) {
                        if (hit != null && hit != table) return null;  // 多表命中
                        hit = table;
                    }
                }
            }
        }
        return hit;
    }

    /** createRow() 默认会给新行每 cell 创建 1 个空 cell（按首行列数对齐），此处只需 setText。
     *  insertNewTableRow(0) 不会自动填充 cell —— 需要我们自己补齐到 {@code cols} 个。 */
    private void fillRowCells(org.apache.poi.xwpf.usermodel.XWPFTableRow row, List<String> cells, int cols) {
        while (row.getTableCells().size() < cols) {
            row.createCell();
        }
        for (int i = 0; i < cols; i++) {
            row.getCell(i).removeParagraph(0);
            org.apache.poi.xwpf.usermodel.XWPFParagraph p = row.getCell(i).addParagraph();
            XWPFRun r = p.createRun();
            r.setText(cells.get(i));
        }
    }

    // ===== 辅助 =====

    static String opType(DocumentPatchOperation op) {
        return switch (op) {
            case ReplaceTextOp r -> "replace_text";
            case InsertParagraphAfterOp r -> "insert_paragraph_after";
            case DeleteParagraphOp r -> "delete_paragraph";
            case AddTableRowOp r -> "add_table_row";
        };
    }

    /** patch 定位失败的受检异常（仅 engine 内部使用，apply 转成 FailedOp）。 */
    static final class PatchLocatorException extends RuntimeException {
        final String reason;
        final int matchCount;
        final String hint;

        private PatchLocatorException(String reason, int matchCount, String hint) {
            super(reason + ": " + hint);
            this.reason = reason;
            this.matchCount = matchCount;
            this.hint = hint;
        }

        static PatchLocatorException notUniqueOrMissing(String reason, String hint) {
            return new PatchLocatorException(reason, 0, hint);
        }

        static PatchLocatorException cellsMismatch(int expected, int actual) {
            return new PatchLocatorException(
                    "cells_mismatch", -1,
                    "cells 长度 " + actual + " 与表格列数 " + expected + " 不符");
        }
    }
}
