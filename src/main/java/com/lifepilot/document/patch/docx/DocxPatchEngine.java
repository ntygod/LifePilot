package com.lifepilot.document.patch.docx;

import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.FailedOp;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
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
 * <p>本 Task 只实现 replace_text；其他 3 个 op 在 Task 7 补齐。</p>
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

    // ===== 以下 3 个 op 在 Task 7 实现，当前先抛 UnsupportedOperationException =====

    private AppliedOp applyInsertAfter(XWPFDocument doc, InsertParagraphAfterOp op) {
        throw new UnsupportedOperationException("insert_paragraph_after 将在 Task 7 实现");
    }

    private AppliedOp applyDeleteParagraph(XWPFDocument doc, DeleteParagraphOp op) {
        throw new UnsupportedOperationException("delete_paragraph 将在 Task 7 实现");
    }

    private AppliedOp applyAddTableRow(XWPFDocument doc, AddTableRowOp op) {
        throw new UnsupportedOperationException("add_table_row 将在 Task 7 实现");
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
