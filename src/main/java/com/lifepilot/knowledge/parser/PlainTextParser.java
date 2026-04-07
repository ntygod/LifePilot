package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本文档解析器。
 *
 * <p>支持 txt、text、log、csv、tsv 扩展名。
 * 编码检测顺序：UTF-8 BOM → UTF-8（无替换字符）→ GBK → ISO-8859-1 兜底。
 * 行尾统一规范化为 LF（\n），按空行分隔段落。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class PlainTextParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PlainTextParser.class);

    private static final List<String> EXTENSIONS = List.of("txt", "text", "log", "csv", "tsv");

    /** UTF-8 BOM 字节序列。 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 段落分隔模式：空行（含仅包含空白字符的行）。 */
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("\\n\\s*\\n");

    /** GBK 字符集名称。 */
    private static final Charset GBK = Charset.forName("GBK");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        // 读取原始字节
        byte[] rawBytes = readRawBytes(filePath);

        // 编码检测并解码
        String content = detectAndDecode(rawBytes);

        // 行尾规范化：\r\n 和 \r 统一为 \n
        content = normalizeLineEndings(content);

        // 段落识别
        List<DocumentElement> elements = identifyParagraphs(content);

        // 构建元数据
        long wordCount = TextUtils.estimateWordCount(content);
        DocumentMetadata metadata = DocumentMetadata.fromFile(filePath, wordCount);

        log.info("纯文本解析完成: file={}, paragraphs={}, wordCount={}",
                filePath, elements.size(), wordCount);

        return new ParseResult(
                content,
                List.copyOf(elements),
                metadata,
                List.of()
        );
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        ParseResult result = parse(filePath);
        return result.metadata();
    }

    /**
     * 读取文件原始字节，失败时抛出 DocumentParseException(Phase.FILE_READ)。
     */
    private byte[] readRawBytes(Path filePath) {
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new DocumentParseException(
                    "纯文本文件读取失败: " + filePath,
                    DocumentParseException.Phase.FILE_READ,
                    filePath.toString(),
                    e
            );
        }
    }

    /**
     * 编码检测并解码原始字节。
     *
     * <p>检测顺序：
     * <ol>
     *   <li>UTF-8 BOM（0xEF, 0xBB, 0xBF）— 存在则跳过 BOM 字节后按 UTF-8 解码</li>
     *   <li>UTF-8 严格解码 — 无替换字符（\uFFFD）则使用</li>
     *   <li>GBK 解码</li>
     *   <li>ISO-8859-1 兜底</li>
     * </ol>
     *
     * @param rawBytes 原始字节
     * @return 解码后的文本
     */
    private String detectAndDecode(byte[] rawBytes) {
        // 1. 检查 UTF-8 BOM
        if (hasUtf8Bom(rawBytes)) {
            log.debug("检测到 UTF-8 BOM，跳过 BOM 字节解码");
            return new String(rawBytes, UTF8_BOM.length, rawBytes.length - UTF8_BOM.length, StandardCharsets.UTF_8);
        }

        // 2. 尝试 UTF-8 严格解码（无替换字符）
        String utf8Result = tryDecodeStrict(rawBytes, StandardCharsets.UTF_8);
        if (utf8Result != null && !utf8Result.contains("\uFFFD")) {
            log.debug("编码检测结果: UTF-8");
            return utf8Result;
        }

        // 3. 尝试 GBK 解码
        String gbkResult = tryDecodeStrict(rawBytes, GBK);
        if (gbkResult != null) {
            log.debug("编码检测结果: GBK");
            return gbkResult;
        }

        // 4. ISO-8859-1 兜底（永远不会失败）
        log.debug("编码检测结果: ISO-8859-1（兜底）");
        return new String(rawBytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * 检查字节数组是否以 UTF-8 BOM 开头。
     */
    private boolean hasUtf8Bom(byte[] bytes) {
        if (bytes.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (bytes[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 使用指定字符集严格解码字节数组。
     *
     * <p>使用 REPORT 错误动作，遇到无法解码的字节时返回 null。
     *
     * @param bytes   原始字节
     * @param charset 字符集
     * @return 解码结果，解码失败返回 null
     */
    private String tryDecodeStrict(byte[] bytes, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer charBuffer = decoder.decode(ByteBuffer.wrap(bytes));
            return charBuffer.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 行尾规范化：\r\n 和 \r 统一为 \n。
     */
    private String normalizeLineEndings(String text) {
        // 先替换 \r\n，再替换剩余的 \r
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 识别段落：按空行（\n\s*\n）分隔文本，每个非空段落生成 Paragraph 元素。
     *
     * @param text 规范化行尾后的文本
     * @return 段落元素列表
     */
    private List<DocumentElement> identifyParagraphs(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<DocumentElement> elements = new ArrayList<>();
        Matcher matcher = PARAGRAPH_SEPARATOR.matcher(text);

        int currentStart = 0;
        while (matcher.find()) {
            String paragraph = text.substring(currentStart, matcher.start()).trim();
            if (!paragraph.isEmpty()) {
                elements.add(new DocumentElement.Paragraph(paragraph, currentStart, matcher.start()));
            }
            currentStart = matcher.end();
        }

        // 处理最后一个段落
        if (currentStart < text.length()) {
            String lastParagraph = text.substring(currentStart).trim();
            if (!lastParagraph.isEmpty()) {
                elements.add(new DocumentElement.Paragraph(lastParagraph, currentStart, text.length()));
            }
        }

        return elements;
    }
}
