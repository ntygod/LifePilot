package com.lifepilot.document.generator;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → DOCX 生成器 —— 基于 Apache POI XWPF。
 *
 * <p>Phase 2A 首版支持的语法：
 * <ul>
 *   <li>{@code # / ## / ###} 一到三级标题</li>
 *   <li>{@code - } / {@code * } 无序列表</li>
 *   <li>{@code 1. } 有序列表</li>
 *   <li>普通段落（空行分段）</li>
 * </ul>
 * 不支持：表格、代码块、内联 bold/italic/link、图片 —— 延 Phase 2A+ 按需扩展。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class MarkdownToDocxGenerator implements DocumentGenerator {

    private static final Logger log = LoggerFactory.getLogger(MarkdownToDocxGenerator.class);
    private static final String MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Pattern ORDERED_LIST = Pattern.compile("^\\d+\\.\\s+(.+)$");
    private static final Pattern HEADING_L1 = Pattern.compile("^#\\s+(.+)$");
    private static final Pattern HEADING_L2 = Pattern.compile("^##\\s+(.+)$");
    private static final Pattern HEADING_L3 = Pattern.compile("^###\\s+(.+)$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^[-*]\\s+(.+)$");

    @Override
    public String mimeType() {
        return MIME_TYPE;
    }

    @Override
    public byte[] generate(String markdown) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            if (markdown == null || markdown.isBlank()) {
                // 空 markdown 也生成最小有效 docx
                doc.createParagraph();
                doc.write(out);
                return out.toByteArray();
            }

            int orderedIdx = 0;
            boolean lastWasOrdered = false;

            for (String rawLine : markdown.split("\\R", -1)) {
                String line = rawLine.stripTrailing();

                if (line.isBlank()) {
                    // 空行作为段落分隔：有序列表序号重置
                    lastWasOrdered = false;
                    orderedIdx = 0;
                    continue;
                }

                Matcher h3 = HEADING_L3.matcher(line);
                Matcher h2 = HEADING_L2.matcher(line);
                Matcher h1 = HEADING_L1.matcher(line);
                Matcher ol = ORDERED_LIST.matcher(line);
                Matcher ul = UNORDERED_LIST.matcher(line);

                // 先匹配更具体的（3 # 之前 2 # 之前 1 #）
                if (h3.matches()) {
                    addHeading(doc, h3.group(1), 3);
                    lastWasOrdered = false;
                } else if (h2.matches()) {
                    addHeading(doc, h2.group(1), 2);
                    lastWasOrdered = false;
                } else if (h1.matches()) {
                    addHeading(doc, h1.group(1), 1);
                    lastWasOrdered = false;
                } else if (ol.matches()) {
                    if (!lastWasOrdered) {
                        orderedIdx = 0;
                    }
                    orderedIdx++;
                    addListItem(doc, orderedIdx + ". " + ol.group(1));
                    lastWasOrdered = true;
                } else if (ul.matches()) {
                    addListItem(doc, "• " + ul.group(1));
                    lastWasOrdered = false;
                } else {
                    addParagraph(doc, line);
                    lastWasOrdered = false;
                }
            }

            doc.write(out);
            log.info("MarkdownToDocx 生成完成：inputChars={}, outputBytes={}",
                    markdown.length(), out.size());
            return out.toByteArray();

        } catch (IOException e) {
            throw new DocumentGenerationException("MarkdownToDocx 生成失败：" + e.getMessage(), e);
        }
    }

    private void addHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.LEFT);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        switch (level) {
            case 1 -> run.setFontSize(20);
            case 2 -> run.setFontSize(16);
            default -> run.setFontSize(14);
        }
    }

    private void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(11);
    }

    private void addListItem(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360);  // 约等于 0.25 inch
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(11);
    }
}
