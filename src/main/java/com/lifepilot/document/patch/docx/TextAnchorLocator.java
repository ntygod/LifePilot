package com.lifepilot.document.patch.docx;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 文本锚点定位器 —— 段内查找 {@code beforeContext + target + afterContext}
 * 拼接串的唯一出现位置，返回 target 部分的 run 范围。
 *
 * <p>设计权衡：只在**同一段落**内定位（POI 的 run 天然段内组织，跨段落定位逻辑复杂且实际
 * 很少见于 LLM 规划）。若 LLM 传来的 before/after context 跨段了，定位会失败，应返回
 * locator_not_found，让 LLM 重试。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class TextAnchorLocator {

    /**
     * 定位 target 在文档中的唯一出现。
     *
     * <p>两阶段定位：</p>
     * <ol>
     *   <li>段内精确匹配（before + target + after 落在同一段）</li>
     *   <li>P1-10 跨段 fallback：把全文段落用 {@code \n} 拼起来，在拼接串里找 full 的唯一出现；
     *       约束 target 本身不跨换行（target 跨段的场景更复杂，留作后续）；
     *       允许 before_context / after_context 跨到邻段</li>
     * </ol>
     *
     * @return Optional.empty 表示 0 次或 >1 次命中；命中时返回唯一 ParagraphRunRange
     */
    public Optional<ParagraphRunRange> locate(XWPFDocument document,
                                              String beforeContext,
                                              String target,
                                              String afterContext) {
        String full = beforeContext + target + afterContext;
        List<ParagraphRunRange> hits = new ArrayList<>();

        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int pIndex = 0; pIndex < paragraphs.size(); pIndex++) {
            XWPFParagraph para = paragraphs.get(pIndex);
            String paraText = para.getText();
            int fromIndex = 0;
            while (true) {
                int hit = paraText.indexOf(full, fromIndex);
                if (hit < 0) break;
                int targetStart = hit + beforeContext.length();
                int targetEnd = targetStart + target.length();
                ParagraphRunRange range = mapCharOffsetToRunRange(para, pIndex, targetStart, targetEnd);
                if (range != null) {
                    hits.add(range);
                    if (hits.size() > 1) return Optional.empty();
                }
                fromIndex = hit + 1;
            }
        }
        if (hits.size() == 1) {
            return Optional.of(hits.get(0));
        }
        if (!hits.isEmpty()) {
            return Optional.empty();  // 段内多命中，直接失败
        }
        return locateCrossParagraph(paragraphs, beforeContext, target, afterContext, full);
    }

    /**
     * P1-10 跨段 fallback：把所有段落用 \n 拼起来在全局字符串里查 full，确保 target 本身
     * 不跨段落边界（含 \n 则放弃）；然后把 target 的全局偏移反向映射回 [paragraphIndex, charOffset]。
     */
    private Optional<ParagraphRunRange> locateCrossParagraph(List<XWPFParagraph> paragraphs,
                                                              String beforeContext,
                                                              String target,
                                                              String afterContext,
                                                              String full) {
        StringBuilder joined = new StringBuilder();
        int[] paragraphStarts = new int[paragraphs.size()];
        for (int i = 0; i < paragraphs.size(); i++) {
            paragraphStarts[i] = joined.length();
            if (i > 0) joined.append('\n');
            String text = paragraphs.get(i).getText();
            joined.append(text == null ? "" : text);
            paragraphStarts[i] = i == 0 ? 0 : paragraphStarts[i];
        }
        // 重算：第 i 段的全局起始 = 前 i-1 段文本长度之和 + (i-1) 个分隔符 \n
        int offset = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            paragraphStarts[i] = offset;
            String text = paragraphs.get(i).getText();
            offset += (text == null ? 0 : text.length()) + 1;  // +1 for '\n'
        }

        String all = joined.toString();
        int firstHit = all.indexOf(full);
        if (firstHit < 0) return Optional.empty();
        int secondHit = all.indexOf(full, firstHit + 1);
        if (secondHit >= 0) return Optional.empty();  // 多处唯一性失败

        int targetGlobalStart = firstHit + beforeContext.length();
        int targetGlobalEnd = targetGlobalStart + target.length();

        // 约束：target 自身不含换行（跨段 target 需要更复杂的 op 语义，留给后续扩展）
        if (all.substring(targetGlobalStart, targetGlobalEnd).indexOf('\n') >= 0) {
            return Optional.empty();
        }

        int pIndex = findParagraphIndex(paragraphStarts, targetGlobalStart);
        if (pIndex < 0) return Optional.empty();
        int intraStart = targetGlobalStart - paragraphStarts[pIndex];
        int intraEnd = targetGlobalEnd - paragraphStarts[pIndex];
        XWPFParagraph para = paragraphs.get(pIndex);
        String paraText = para.getText();
        if (paraText == null || intraEnd > paraText.length()) return Optional.empty();

        ParagraphRunRange range = mapCharOffsetToRunRange(para, pIndex, intraStart, intraEnd);
        return range == null ? Optional.empty() : Optional.of(range);
    }

    /** 在 paragraphStarts 中找第一个 start ≤ targetGlobal 的段落（简单线性扫描，段数通常 < 1000 可接受）。 */
    private static int findParagraphIndex(int[] paragraphStarts, int targetGlobal) {
        for (int i = paragraphStarts.length - 1; i >= 0; i--) {
            if (paragraphStarts[i] <= targetGlobal) return i;
        }
        return -1;
    }

    /**
     * 直接按段落完整文本查找（InsertParagraphAfterOp / DeleteParagraphOp 使用）。
     *
     * @return 命中唯一段落的下标；0 或 >1 返回 -1
     */
    public int locateParagraphIndexByFullText(XWPFDocument document, String paragraphFullText) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        int hitIndex = -1;
        for (int i = 0; i < paragraphs.size(); i++) {
            if (paragraphs.get(i).getText().equals(paragraphFullText)) {
                if (hitIndex != -1) return -1;  // 多命中
                hitIndex = i;
            }
        }
        return hitIndex;
    }

    /** 把段内字符绝对偏移 [start, end) 映射到 run 范围。 */
    private ParagraphRunRange mapCharOffsetToRunRange(XWPFParagraph para, int paragraphIndex,
                                                       int charStart, int charEnd) {
        List<XWPFRun> runs = para.getRuns();
        int cursor = 0;
        int startRunIdx = -1, startOffset = 0;
        int endRunIdx = -1, endOffset = 0;
        for (int i = 0; i < runs.size(); i++) {
            String runText = runs.get(i).text();
            if (runText == null) runText = "";
            int runLen = runText.length();
            int runStart = cursor;
            int runEnd = cursor + runLen;

            if (startRunIdx == -1 && charStart >= runStart && charStart < runEnd) {
                startRunIdx = i;
                startOffset = charStart - runStart;
            }
            if (charEnd > runStart && charEnd <= runEnd) {
                endRunIdx = i;
                endOffset = charEnd - runStart;
            }
            cursor = runEnd;
        }
        if (startRunIdx < 0 || endRunIdx < 0) return null;
        return new ParagraphRunRange(paragraphIndex, startRunIdx, startOffset, endRunIdx, endOffset);
    }
}
