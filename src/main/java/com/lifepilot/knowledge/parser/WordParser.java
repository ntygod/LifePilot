package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.util.TextUtils;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.ooxml.POIXMLProperties;
import org.openxmlformats.schemas.officeDocument.x2006.extendedProperties.CTProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Word/DOCX 文档解析器 — 基于 Apache POI。
 *
 * <p>按段落提取文本，通过样式识别标题层级，完整提取表格结构。
 * 仅支持 DOCX（Office 2007+），旧版 .doc 抛出 {@link DocumentParseException}。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class WordParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(WordParser.class);

    private static final List<String> EXTENSIONS = List.of("docx");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        validateFormat(filePath);

        try (InputStream is = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(is)) {

            StringBuilder fullText = new StringBuilder();
            List<DocumentElement> elements = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // 遍历文档体元素（段落和表格按顺序出现）
            for (IBodyElement element : document.getBodyElements()) {
                int offset = fullText.length();

                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText().trim();
                    if (text.isEmpty()) {
                        fullText.append("\n");
                        continue;
                    }

                    // 检测标题样式
                    int headingLevel = detectHeadingLevel(paragraph);
                    if (headingLevel > 0) {
                        elements.add(new DocumentElement.Heading(
                                headingLevel, text, offset, offset + text.length()));
                    } else {
                        elements.add(new DocumentElement.Paragraph(
                                text, offset, offset + text.length()));
                    }
                    fullText.append(text).append("\n");

                } else if (element instanceof XWPFTable table) {
                    parseTable(table, fullText, offset, elements);
                }
            }

            String text = fullText.toString();
            long wordCount = TextUtils.estimateWordCount(text);
            DocumentMetadata metadata = extractMetadataFromDocument(document, filePath, wordCount);

            log.info("DOCX 解析完成: file={}, elements={}, wordCount={}",
                    filePath, elements.size(), wordCount);

            return new ParseResult(
                    text,
                    List.copyOf(elements),
                    metadata,
                    List.copyOf(warnings)
            );
        } catch (DocumentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentParseException(
                    "DOCX 文件解码失败: " + filePath + " — " + e.getMessage(),
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString(),
                    e
            );
        }
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        validateFormat(filePath);
        try (InputStream is = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(is)) {
            return extractMetadataFromDocument(document, filePath, 0);
        } catch (IOException e) {
            throw new DocumentParseException(
                    "DOCX 元数据提取失败: " + filePath,
                    DocumentParseException.Phase.METADATA_EXTRACTION,
                    filePath.toString(),
                    e
            );
        }
    }

    /**
     * 验证文件格式，旧版 .doc 抛出异常。
     */
    private void validateFormat(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".doc") && !fileName.endsWith(".docx")) {
            throw new DocumentParseException(
                    "不支持旧版 .doc 格式，请转换为 .docx: " + filePath,
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString()
            );
        }
    }

    /**
     * 通过段落样式检测标题级别，返回 1-6，非标题返回 0。
     *
     * <p>支持英文（Heading1 / heading 1）和中文（标题1 / 标题 1）样式名。
     */
    private int detectHeadingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return 0;
        }
        String lower = style.toLowerCase();
        for (int level = 1; level <= 6; level++) {
            if (lower.equals("heading" + level)
                    || lower.equals("heading " + level)
                    || style.equals("标题" + level)
                    || style.equals("标题 " + level)) {
                return level;
            }
        }
        return 0;
    }

    /**
     * 解析表格，第一行为表头。
     */
    private void parseTable(XWPFTable table, StringBuilder fullText,
                            int baseOffset, List<DocumentElement> elements) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }

        // 第一行为表头
        List<String> headers = rows.getFirst().getTableCells().stream()
                .map(cell -> cell.getText().trim())
                .toList();

        // 后续行为数据
        List<List<String>> dataRows = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i).getTableCells().stream()
                    .map(cell -> cell.getText().trim())
                    .toList();
            dataRows.add(List.copyOf(row));
        }

        // 构建表格文本表示
        StringBuilder tableText = new StringBuilder();
        tableText.append("| ").append(String.join(" | ", headers)).append(" |\n");
        for (List<String> row : dataRows) {
            tableText.append("| ").append(String.join(" | ", row)).append(" |\n");
        }

        int startOffset = fullText.length();
        fullText.append(tableText);
        int endOffset = fullText.length();

        elements.add(new DocumentElement.Table(
                List.copyOf(headers),
                dataRows.stream().map(List::copyOf).toList(),
                startOffset,
                endOffset
        ));
    }

    /**
     * 从 XWPFDocument 提取元数据。
     */
    private DocumentMetadata extractMetadataFromDocument(XWPFDocument document,
                                                         Path filePath, long wordCount) {
        POIXMLProperties.CoreProperties core = document.getProperties().getCoreProperties();

        Optional<String> title = Optional.ofNullable(core.getTitle())
                .filter(t -> !t.isBlank());
        Optional<String> author = Optional.ofNullable(core.getCreator())
                .filter(a -> !a.isBlank());
        Optional<Instant> createdAt = Optional.ofNullable(core.getCreated())
                .map(Date::toInstant);
        Optional<Instant> modifiedAt = Optional.ofNullable(core.getModified())
                .map(Date::toInstant);

        // 无标题时使用文件名
        if (title.isEmpty()) {
            String fileName = filePath.getFileName().toString();
            int dotIndex = fileName.lastIndexOf('.');
            title = Optional.of(dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName);
        }

        // 尝试获取页数
        int pageCount = 0;
        try {
            CTProperties extProps = document.getProperties().getExtendedProperties().getUnderlyingProperties();
            pageCount = extProps.getPages();
        } catch (Exception e) {
            log.debug("无法获取 DOCX 页数: {}", e.getMessage());
        }

        return new DocumentMetadata(
                title, author, createdAt, modifiedAt,
                pageCount, wordCount,
                Optional.empty(),
                Map.of()
        );
    }
}
