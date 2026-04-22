package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.util.TextUtils;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PowerPoint/PPTX 文档解析器 —— 基于 Apache POI XSLF。
 *
 * <p>按幻灯片顺序遍历,每张幻灯片作为一个一级标题章节输出;遍历时提取文本占位符
 * (标题、正文、文本框等)以及备注文本。表格和图表不做深度结构化提取
 * ({@link XSLFTextShape} 的统一文本抽取已能满足 LLM 读文字的需求)。</p>
 *
 * <p>元数据中 {@code pageCount} 字段语义化复用为幻灯片数量。</p>
 *
 * <p>仅支持 PPTX (Office 2007+),旧版 .ppt 不支持。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public non-sealed class PowerpointParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PowerpointParser.class);

    private static final List<String> EXTENSIONS = List.of("pptx");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        validateFormat(filePath);

        try (InputStream is = Files.newInputStream(filePath);
             XMLSlideShow ppt = new XMLSlideShow(is)) {

            StringBuilder fullText = new StringBuilder();
            List<DocumentElement> elements = new ArrayList<>();
            int slideIdx = 0;

            for (XSLFSlide slide : ppt.getSlides()) {
                slideIdx++;

                // 幻灯片索引作为一级标题
                int headingStart = fullText.length();
                String heading = "幻灯片 " + slideIdx;
                fullText.append("# ").append(heading).append("\n");
                elements.add(new DocumentElement.Heading(
                        1, heading, headingStart, fullText.length()));

                // 遍历当前幻灯片的所有形状,提取文本占位符
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            String stripped = text.strip();
                            int start = fullText.length();
                            fullText.append(stripped).append("\n");
                            elements.add(new DocumentElement.Paragraph(
                                    stripped, start, fullText.length()));
                        }
                    }
                }

                // 提取备注文本(若存在)
                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder notesText = new StringBuilder();
                    for (XSLFShape ns : notes.getShapes()) {
                        if (ns instanceof XSLFTextShape nts) {
                            String t = nts.getText();
                            if (t != null && !t.isBlank()) {
                                if (!notesText.isEmpty()) {
                                    notesText.append("\n");
                                }
                                notesText.append(t.strip());
                            }
                        }
                    }
                    if (!notesText.isEmpty()) {
                        int start = fullText.length();
                        String notesBlock = "[备注] " + notesText;
                        fullText.append(notesBlock).append("\n");
                        elements.add(new DocumentElement.Paragraph(
                                notesBlock, start, fullText.length()));
                    }
                }

                // 幻灯片间空行分隔
                fullText.append("\n");
            }

            String text = fullText.toString();
            long wordCount = TextUtils.estimateWordCount(text);
            DocumentMetadata metadata = new DocumentMetadata(
                    Optional.of(stripExtension(filePath.getFileName().toString())),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    slideIdx,
                    wordCount,
                    Optional.empty(),
                    Map.of()
            );

            log.info("PPTX 解析完成: file={}, slides={}, elements={}, wordCount={}",
                    filePath, slideIdx, elements.size(), wordCount);

            return new ParseResult(text, List.copyOf(elements), metadata, List.of());

        } catch (DocumentParseException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException(
                    "PPTX 文件解码失败: " + filePath + " — " + e.getMessage(),
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString(),
                    e);
        }
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        validateFormat(filePath);
        try (InputStream is = Files.newInputStream(filePath);
             XMLSlideShow ppt = new XMLSlideShow(is)) {
            int slides = ppt.getSlides().size();
            return new DocumentMetadata(
                    Optional.of(stripExtension(filePath.getFileName().toString())),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    slides,
                    0,
                    Optional.empty(),
                    Map.of()
            );
        } catch (IOException | RuntimeException e) {
            log.warn("提取 PPTX 元数据失败,返回默认值: file={}, error={}", filePath, e.getMessage());
            return DocumentMetadata.empty();
        }
    }

    /**
     * 验证文件格式,仅接受 .pptx 扩展名;非法扩展名直接抛异常。
     */
    private void validateFormat(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".pptx")) {
            throw new DocumentParseException(
                    "仅支持 PPTX 格式,不支持: " + fileName,
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString());
        }
    }

    /**
     * 去除文件名扩展名,用作默认标题。
     */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
