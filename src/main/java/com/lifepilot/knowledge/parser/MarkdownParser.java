package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档解析器。
 *
 * <p>支持 md、markdown、mkd 扩展名。基于正则解析 ATX 标题、围栏代码块、
 * GFM 表格和 YAML Front Matter。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class MarkdownParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownParser.class);

    private static final List<String> EXTENSIONS = List.of("md", "markdown", "mkd");

    // ATX 标题：# ~ ######
    private static final Pattern ATX_HEADING = Pattern.compile(
            "^(#{1,6})\\s+(.+)", Pattern.MULTILINE);

    // 围栏代码块：```language ... ``` 或 ~~~language ... ~~~
    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile(
            "(?:```|~~~)(\\w*)\\n([\\s\\S]*?)(?:```|~~~)", Pattern.MULTILINE);

    // GFM 表格：表头行 + 分隔行 + 数据行
    private static final Pattern GFM_TABLE = Pattern.compile(
            "(\\|.+\\|\\n)(\\|[-: |]+\\|\\n)((?:\\|.+\\|\\n)*)", Pattern.MULTILINE);

    // YAML Front Matter：--- 包裹的元数据块（必须位于文件开头）
    private static final Pattern YAML_FRONT_MATTER = Pattern.compile(
            "\\A---\\n([\\s\\S]*?)\\n---\\n");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        String content = readFile(filePath);
        List<DocumentElement> elements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 解析 YAML Front Matter 并从正文中剥离（避免元数据文本污染分块和 embedding）
        Map<String, String> frontMatter = parseFrontMatter(content);
        Matcher fmMatcher = YAML_FRONT_MATTER.matcher(content);
        int fmEndOffset = 0;
        String textContent = content;
        if (fmMatcher.find()) {
            fmEndOffset = fmMatcher.end();
            textContent = content.substring(fmEndOffset);
        }

        // 以下解析基于剥离 Front Matter 后的 textContent
        // 解析围栏代码块（先于标题，避免代码块内的 # 被误识别）
        parseCodeBlocks(textContent, elements);

        // 解析 GFM 表格
        parseTables(textContent, elements);

        // 解析 ATX 标题（排除代码块内的标题）
        parseHeadings(textContent, elements);

        // 按 startOffset 升序排列
        elements.sort(Comparator.comparingInt(DocumentElement::startOffset));

        // 构建元数据
        long wordCount = TextUtils.estimateWordCount(textContent);
        DocumentMetadata metadata = buildMetadata(filePath, frontMatter, elements, wordCount);

        log.info("Markdown 解析完成: file={}, elements={}, wordCount={}",
                filePath, elements.size(), wordCount);

        return new ParseResult(
                textContent,
                List.copyOf(elements),
                metadata,
                List.copyOf(warnings)
        );
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        ParseResult result = parse(filePath);
        return result.metadata();
    }

    /**
     * 读取文件内容，失败时抛出 DocumentParseException(Phase.FILE_READ)。
     */
    private String readFile(Path filePath) {
        try {
            // 统一换行符为 \n，避免 Windows \r\n 导致正则匹配失败
            return Files.readString(filePath, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new DocumentParseException(
                    "Markdown 文件读取失败: " + filePath,
                    DocumentParseException.Phase.FILE_READ,
                    filePath.toString(),
                    e
            );
        }
    }

    /**
     * 解析 YAML Front Matter，返回 key-value 映射。
     */
    private Map<String, String> parseFrontMatter(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = YAML_FRONT_MATTER.matcher(content);
        if (matcher.find()) {
            String yamlBlock = matcher.group(1);
            for (String line : yamlBlock.split("\\n")) {
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();
                    // 去除可能的引号包裹
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!key.isEmpty()) {
                        result.put(key, value);
                    }
                }
            }
            log.debug("解析 YAML Front Matter: keys={}", result.keySet());
        }
        return result;
    }

    /**
     * 解析 ATX 标题，排除位于代码块内的标题。
     */
    private void parseHeadings(String content, List<DocumentElement> elements) {
        // 收集代码块区间，用于排除代码块内的标题
        List<int[]> codeBlockRanges = new ArrayList<>();
        Matcher codeMatcher = FENCED_CODE_BLOCK.matcher(content);
        while (codeMatcher.find()) {
            codeBlockRanges.add(new int[]{codeMatcher.start(), codeMatcher.end()});
        }

        // 也排除 YAML Front Matter 区间
        Matcher fmMatcher = YAML_FRONT_MATTER.matcher(content);
        if (fmMatcher.find()) {
            codeBlockRanges.add(new int[]{fmMatcher.start(), fmMatcher.end()});
        }

        Matcher matcher = ATX_HEADING.matcher(content);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            // 排除代码块和 Front Matter 内的标题
            if (isInsideRange(start, codeBlockRanges)) {
                continue;
            }

            int level = matcher.group(1).length();
            String text = matcher.group(2).trim();
            elements.add(new DocumentElement.Heading(level, text, start, end));
        }
    }

    /**
     * 解析围栏代码块。
     */
    private void parseCodeBlocks(String content, List<DocumentElement> elements) {
        Matcher matcher = FENCED_CODE_BLOCK.matcher(content);
        while (matcher.find()) {
            String language = matcher.group(1);
            String code = matcher.group(2);
            Optional<String> lang = (language == null || language.isEmpty())
                    ? Optional.empty()
                    : Optional.of(language);
            elements.add(new DocumentElement.CodeBlock(lang, code, matcher.start(), matcher.end()));
        }
    }

    /**
     * 解析 GFM 表格。
     */
    private void parseTables(String content, List<DocumentElement> elements) {
        Matcher matcher = GFM_TABLE.matcher(content);
        while (matcher.find()) {
            String headerLine = matcher.group(1).trim();
            String dataLines = matcher.group(3);

            List<String> headers = parseTableRow(headerLine);
            List<List<String>> rows = new ArrayList<>();
            if (dataLines != null && !dataLines.isEmpty()) {
                for (String rowLine : dataLines.split("\\n")) {
                    String trimmed = rowLine.trim();
                    if (!trimmed.isEmpty()) {
                        rows.add(parseTableRow(trimmed));
                    }
                }
            }

            elements.add(new DocumentElement.Table(
                    List.copyOf(headers),
                    rows.stream().map(List::copyOf).toList(),
                    matcher.start(),
                    matcher.end()
            ));
        }
    }

    /**
     * 解析表格行，按 | 分隔并去除首尾空白。
     */
    private List<String> parseTableRow(String line) {
        // 去除首尾的 |
        String trimmed = line;
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Arrays.stream(trimmed.split("\\|"))
                .map(String::trim)
                .toList();
    }

    /**
     * 判断指定偏移量是否位于给定区间列表内。
     */
    private boolean isInsideRange(int offset, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (offset >= range[0] && offset < range[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建文档元数据，优先使用 Front Matter 中的信息。
     */
    private DocumentMetadata buildMetadata(Path filePath, Map<String, String> frontMatter,
                                           List<DocumentElement> elements, long wordCount) {
        // 标题优先级：Front Matter title > 第一个标题
        Optional<String> title = Optional.ofNullable(frontMatter.get("title"))
                .filter(t -> !t.isEmpty());
        if (title.isEmpty()) {
            title = elements.stream()
                    .filter(e -> e instanceof DocumentElement.Heading)
                    .map(e -> ((DocumentElement.Heading) e).text())
                    .findFirst();
        }

        Optional<String> author = Optional.ofNullable(frontMatter.get("author"))
                .filter(a -> !a.isEmpty());

        Optional<Instant> createdAt = Optional.ofNullable(frontMatter.get("date"))
                .filter(d -> !d.isEmpty())
                .flatMap(this::parseInstant);

        Optional<String> language = Optional.ofNullable(frontMatter.get("lang"))
                .filter(l -> !l.isEmpty());

        // 将 Front Matter 中非标准字段放入 extraProperties
        Map<String, String> extraProperties = new LinkedHashMap<>(frontMatter);
        extraProperties.remove("title");
        extraProperties.remove("author");
        extraProperties.remove("date");
        extraProperties.remove("lang");

        return new DocumentMetadata(
                title,
                author,
                createdAt,
                Optional.empty(),
                0,
                wordCount,
                language,
                Map.copyOf(extraProperties)
        );
    }

    /**
     * 尝试将日期字符串解析为 Instant。
     * 依次尝试 ISO Instant（2026-02-25T10:15:30Z）→ LocalDate（2026-02-25）→ LocalDateTime。
     */
    private Optional<Instant> parseInstant(String dateStr) {
        // 1. ISO Instant 格式
        try {
            return Optional.of(Instant.parse(dateStr));
        } catch (Exception ignored) {}
        // 2. LocalDate 格式（Front Matter 最常见的日期格式）
        try {
            return Optional.of(LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (Exception ignored) {}
        // 3. LocalDateTime 格式
        try {
            return Optional.of(LocalDateTime.parse(dateStr).toInstant(ZoneOffset.UTC));
        } catch (Exception ignored) {}
        log.debug("日期解析失败，忽略: value={}", dateStr);
        return Optional.empty();
    }
}
