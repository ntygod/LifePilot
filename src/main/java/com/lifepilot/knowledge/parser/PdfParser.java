package com.lifepilot.knowledge.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 文档解析器 — 基于 Apache PDFBox 3.x。
 *
 * <p>逐页提取文本，支持部分解析（单页失败跳过），提取元数据。
 * 加密 PDF 抛出 {@link DocumentParseException}(Phase.FORMAT_DECODE)。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class PdfParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfParser.class);

    private static final List<String> EXTENSIONS = List.of("pdf");

    /** 编号标题模式：如 "1.2 标题" 或 "第一章 标题" */
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(\\d+(?:\\.\\d+)*)\\s+(.+)$");

    /** 短行全大写标题（英文） */
    private static final Pattern UPPERCASE_HEADING = Pattern.compile(
            "^[A-Z][A-Z\\s]{2,60}$");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            if (document.isEncrypted()) {
                throw new DocumentParseException(
                        "PDF 文件已加密，无法解析: " + filePath,
                        DocumentParseException.Phase.FORMAT_DECODE,
                        filePath.toString()
                );
            }

            int pageCount = document.getNumberOfPages();
            StringBuilder fullText = new StringBuilder();
            List<DocumentElement> elements = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // 逐页提取文本
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pageCount; page++) {
                try {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String pageText = stripper.getText(document);
                    int offset = fullText.length();
                    fullText.append(pageText);

                    // 启发式标题检测
                    detectHeadings(pageText, offset, elements);
                } catch (Exception e) {
                    String msg = "PDF 第 " + page + " 页解析失败，已跳过: " + e.getMessage();
                    warnings.add(msg);
                    log.warn("PDF 页面解析失败: file={}, page={}, error={}", filePath, page, e.getMessage());
                }
            }

            String text = fullText.toString();
            long wordCount = estimateWordCount(text);
            DocumentMetadata metadata = extractMetadataFromDocument(document, filePath, wordCount);

            log.info("PDF 解析完成: file={}, pages={}, elements={}, warnings={}",
                    filePath, pageCount, elements.size(), warnings.size());

            return new ParseResult(
                    text,
                    List.copyOf(elements),
                    metadata,
                    List.copyOf(warnings)
            );
        } catch (DocumentParseException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentParseException(
                    "PDF 文件读取失败: " + filePath,
                    DocumentParseException.Phase.FILE_READ,
                    filePath.toString(),
                    e
            );
        } catch (Exception e) {
            throw new DocumentParseException(
                    "PDF 文件解码失败: " + filePath + " — " + e.getMessage(),
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString(),
                    e
            );
        }
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            return extractMetadataFromDocument(document, filePath, 0);
        } catch (IOException e) {
            throw new DocumentParseException(
                    "PDF 元数据提取失败: " + filePath,
                    DocumentParseException.Phase.METADATA_EXTRACTION,
                    filePath.toString(),
                    e
            );
        }
    }

    /**
     * 从 PDDocument 提取元数据。
     */
    private DocumentMetadata extractMetadataFromDocument(PDDocument document, Path filePath, long wordCount) {
        PDDocumentInformation info = document.getDocumentInformation();
        int pageCount = document.getNumberOfPages();

        Optional<String> title = Optional.ofNullable(info.getTitle())
                .filter(t -> !t.isBlank());
        Optional<String> author = Optional.ofNullable(info.getAuthor())
                .filter(a -> !a.isBlank());
        Optional<Instant> createdAt = Optional.ofNullable(info.getCreationDate())
                .map(cal -> cal.toInstant());
        Optional<Instant> modifiedAt = Optional.ofNullable(info.getModificationDate())
                .map(cal -> cal.toInstant());

        // 无标题时使用文件名
        if (title.isEmpty()) {
            String fileName = filePath.getFileName().toString();
            int dotIndex = fileName.lastIndexOf('.');
            title = Optional.of(dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName);
        }

        Map<String, String> extra = new LinkedHashMap<>();
        if (info.getSubject() != null && !info.getSubject().isBlank()) {
            extra.put("subject", info.getSubject());
        }
        if (info.getKeywords() != null && !info.getKeywords().isBlank()) {
            extra.put("keywords", info.getKeywords());
        }
        if (info.getProducer() != null && !info.getProducer().isBlank()) {
            extra.put("producer", info.getProducer());
        }

        return new DocumentMetadata(
                title, author, createdAt, modifiedAt,
                pageCount, wordCount,
                Optional.empty(),
                Map.copyOf(extra)
        );
    }

    /**
     * 启发式标题检测：编号模式（"1.2 标题"）+ 短行全大写。
     */
    private void detectHeadings(String pageText, int baseOffset, List<DocumentElement> elements) {
        String[] lines = pageText.split("\n");
        int lineOffset = baseOffset;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                int level = detectHeadingLevel(trimmed);
                if (level > 0) {
                    elements.add(new DocumentElement.Heading(
                            level, trimmed, lineOffset, lineOffset + line.length()));
                }
            }
            lineOffset += line.length() + 1; // +1 for \n
        }
    }

    /**
     * 检测行是否为标题，返回标题级别（1-3），非标题返回 0。
     */
    private int detectHeadingLevel(String line) {
        // 编号标题：根据编号层级推断标题级别
        Matcher numbered = NUMBERED_HEADING.matcher(line);
        if (numbered.matches() && line.length() <= 80) {
            String number = numbered.group(1);
            int dots = (int) number.chars().filter(c -> c == '.').count();
            return Math.min(dots + 1, 3);
        }

        // 短行全大写（英文标题）
        if (UPPERCASE_HEADING.matcher(line).matches()) {
            return 1;
        }

        return 0;
    }

    /**
     * 估算字数：中文按字符数，英文按空格分词。
     */
    private long estimateWordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long chineseChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long totalWords = Arrays.stream(text.split("\\s+"))
                .filter(w -> !w.isEmpty())
                .count();
        return chineseChars + Math.max(0, totalWords - chineseChars);
    }
}
