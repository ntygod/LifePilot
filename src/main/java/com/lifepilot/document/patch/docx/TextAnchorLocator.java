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
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
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
