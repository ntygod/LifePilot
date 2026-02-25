package com.lifepilot.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.lifepilot.knowledge.parser.DocumentParseException;
import com.lifepilot.knowledge.parser.DocumentParser;
import com.lifepilot.knowledge.parser.ParseResult;

/**
 * 文档内容提取器。
 * <p>
 * 组合知识库模块已有的 {@link DocumentParser} 实现，通过 MIME 类型匹配选择对应的解析器，
 * 将 byte[] 写入临时文件后委托解析器处理，返回提取的纯文本。
 *
 * @author zsg
 * @since 2026-07-01
 */
public class DocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractor.class);

    /** MIME 类型到文件扩展名的映射 */
    private static final Map<String, String> MIME_TO_EXTENSION = Map.of(
            "application/pdf", "pdf",
            "application/msword", "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx",
            "text/markdown", "md",
            "text/plain", "txt"
    );

    private final List<DocumentParser> parsers;

    /**
     * 构造文档提取器。
     *
     * @param parsers Spring 自动收集的所有 DocumentParser Bean
     */
    public DocumentExtractor(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 从文档中提取纯文本。
     *
     * @param data     文档二进制数据
     * @param mimeType 文档 MIME 类型
     * @param fileName 原始文件名（可选）
     * @return 提取的纯文本
     * @throws DocumentExtractionException 不支持的格式或解析失败
     */
    public String extract(byte[] data, String mimeType, @Nullable String fileName) {
        // 1. 根据 MIME 类型查找对应的文件扩展名
        String extension = MIME_TO_EXTENSION.get(mimeType);
        if (extension == null) {
            throw new DocumentExtractionException("不支持的文档格式: " + mimeType);
        }

        // 2. 查找匹配的 DocumentParser
        DocumentParser parser = findParser(extension);
        if (parser == null) {
            throw new DocumentExtractionException("未找到支持 " + extension + " 格式的解析器");
        }

        // 3. 写入临时文件并委托解析器处理
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("doc-extract-", "." + extension);
            Files.write(tempFile, data);
            log.debug("文档写入临时文件: path={}, mimeType={}, size={}", tempFile, mimeType, data.length);

            ParseResult result = parser.parse(tempFile);
            return result.text();
        } catch (DocumentParseException e) {
            throw new DocumentExtractionException("文档解析失败: " + mimeType, e);
        } catch (IOException e) {
            throw new DocumentExtractionException("临时文件操作失败", e);
        } finally {
            // 确保临时文件删除
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("临时文件删除失败: path={}", tempFile, e);
                }
            }
        }
    }

    /**
     * 根据扩展名查找匹配的解析器。
     */
    @Nullable
    private DocumentParser findParser(String extension) {
        return parsers.stream()
                .filter(p -> p.supportedExtensions().contains(extension))
                .findFirst()
                .orElse(null);
    }
}
