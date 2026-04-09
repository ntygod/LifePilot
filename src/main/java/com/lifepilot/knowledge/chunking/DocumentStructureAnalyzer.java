package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.parser.DocumentElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 文档结构分析器（Layer 1）— 对每一行进行结构分类，然后合并为连续同类型区域。
 *
 * <p>分析流程：
 * <ol>
 *   <li>将文本按行拆分，逐行判定结构类型</li>
 *   <li>当解析器元素可用时，优先使用解析器结果覆盖行分类</li>
 *   <li>将连续同类型行合并为 {@link StructureRegion}</li>
 *   <li>维护标题层级堆栈，为每个区域记录标题上下文</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-09
 */
public class DocumentStructureAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DocumentStructureAnalyzer.class);

    /** ATX 标题正则（1-6 级） */
    private static final Pattern ATX_HEADING = Pattern.compile("^#{1,6}\\s+.+");

    /** 列表项正则（无序/有序/中文序号，不含"第X章/节"式章节标题） */
    private static final Pattern LIST_MARKER = Pattern.compile(
            "^(\\s*)([-*+]|\\d+[.、)]|[（(]\\d+[)）]|（[一二三四五六七八九十百]+）|第[一二三四五六七八九十百\\d]+[条款项])\\s+");

    /** 中文章节标题正则（第X章/节/篇/卷 + 标题文本） */
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^第[一二三四五六七八九十百千零\\d]+[章节篇卷]\\s+.+");

    /** 表格分隔行正则 */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^[\\s|:+-]+$");

    /** 中英文句末标点 */
    private static final String SENTENCE_ENDINGS = "。！？；.!?;,，";

    /** 对话/引用开头字符 — 以这些字符开头的短行是对话而非标题 */
    private static final String DIALOGUE_OPENERS = "\u201c\u300c\u300e\u2018'\"(";

    private final int maxHeadingLength;
    private final int minCodeIndent;
    @SuppressWarnings("unused")
    private final int minTableColumns;

    /**
     * 构造文档结构分析器。
     *
     * @param maxHeadingLength 纯文本标题最大字符数
     * @param minCodeIndent    缩进代码块最小缩进空格数
     * @param minTableColumns  表格最小列数
     */
    public DocumentStructureAnalyzer(int maxHeadingLength, int minCodeIndent, int minTableColumns) {
        this.maxHeadingLength = maxHeadingLength;
        this.minCodeIndent = minCodeIndent;
        this.minTableColumns = minTableColumns;
        log.debug("初始化 DocumentStructureAnalyzer: maxHeadingLength={}, minCodeIndent={}, minTableColumns={}",
                maxHeadingLength, minCodeIndent, minTableColumns);
    }

    /**
     * 分析文档结构（无解析器元素辅助）。
     *
     * @param text 文档全文
     * @return 结构区域列表
     */
    public List<StructureRegion> analyze(String text) {
        return analyze(text, null);
    }

    /**
     * 分析文档结构，可选利用解析器提取的元素辅助分类。
     *
     * @param text           文档全文
     * @param parserElements 解析器提取的文档元素（可选）
     * @return 结构区域列表
     */
    public List<StructureRegion> analyze(String text, @Nullable List<DocumentElement> parserElements) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // 1. 按行拆分并计算偏移量
        String[] rawLines = text.split("\n", -1);
        var annotatedLines = new AnnotatedLine[rawLines.length];
        int offset = 0;
        for (int i = 0; i < rawLines.length; i++) {
            int endOffset = offset + rawLines[i].length();
            // 行类型先初始化为 null，后续填充
            annotatedLines[i] = new AnnotatedLine(i, offset, endOffset, null, rawLines[i]);
            // +1 跳过 \n 分隔符（最后一行之后没有 \n，但 endOffset 不需要额外调整）
            offset = endOffset + 1;
        }

        // 2. 如果有解析器元素，先使用解析器结果覆盖行分类
        StructureType[] lineTypes = new StructureType[rawLines.length];
        if (parserElements != null && !parserElements.isEmpty()) {
            overlayParserElements(annotatedLines, lineTypes, parserElements);
        }

        // 3. 对未被解析器覆盖的行进行规则分类
        classifyLines(rawLines, lineTypes, annotatedLines);

        // 4. 用最终类型重建 AnnotatedLine 数组
        for (int i = 0; i < annotatedLines.length; i++) {
            var old = annotatedLines[i];
            annotatedLines[i] = new AnnotatedLine(old.lineNumber(), old.startOffset(), old.endOffset(),
                    lineTypes[i], old.rawText());
        }

        // 5. 合并连续同类型行为区域，并维护标题层级
        var regions = groupIntoRegions(annotatedLines, text);

        log.debug("文档结构分析完成: 总行数={}, 区域数={}", rawLines.length, regions.size());
        return regions;
    }

    // ---- 解析器元素覆盖 ----

    /**
     * 将解析器提取的结构元素映射到行级分类。
     */
    private void overlayParserElements(AnnotatedLine[] lines, StructureType[] lineTypes,
                                        List<DocumentElement> elements) {
        for (var element : elements) {
            StructureType mappedType = switch (element) {
                case DocumentElement.Heading _ -> StructureType.HEADING;
                case DocumentElement.CodeBlock _ -> StructureType.CODE;
                case DocumentElement.Table _ -> StructureType.TABLE;
                case DocumentElement.ListBlock _ -> StructureType.LIST;
                // Paragraph 和 Image 不覆盖行分类 — Paragraph 是兜底类型，
                // 覆盖后会阻止规则分类器的标题启发式等检测逻辑
                case DocumentElement.Paragraph _, DocumentElement.Image _ -> null;
            };
            if (mappedType == null) continue;

            int startOff = element.startOffset();
            int endOff = element.endOffset();

            // 查找落在元素偏移范围内的行
            for (int i = 0; i < lines.length; i++) {
                // 行与元素有重叠
                if (lines[i].endOffset() > startOff && lines[i].startOffset() < endOff) {
                    // Paragraph 类型只在行尚未被分类时才覆盖（低优先级）
                    if (mappedType == StructureType.PARAGRAPH && lineTypes[i] != null) {
                        continue;
                    }
                    lineTypes[i] = mappedType;
                }
            }
        }
    }

    // ---- 规则分类 ----

    /**
     * 对未被解析器覆盖的行进行基于规则的分类。
     */
    private void classifyLines(String[] rawLines, StructureType[] lineTypes, AnnotatedLine[] annotated) {
        boolean inFence = false;

        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];

            // 围栏代码块状态跟踪 — 即使已被解析器分类也要更新围栏状态
            if (isFenceBoundary(line)) {
                inFence = !inFence;
                if (lineTypes[i] == null) {
                    lineTypes[i] = StructureType.CODE;
                }
                continue;
            }
            if (inFence) {
                if (lineTypes[i] == null) {
                    lineTypes[i] = StructureType.CODE;
                }
                continue;
            }

            // 已被解析器分类的行跳过
            if (lineTypes[i] != null) {
                continue;
            }

            // 按优先级分类
            lineTypes[i] = classifySingleLine(line, i, rawLines, lineTypes);
        }
    }

    /**
     * 分类单行（优先级：空行 → 标题 → 表格 → 列表 → 缩进代码 → 段落）。
     */
    private StructureType classifySingleLine(String line, int lineIndex, String[] allLines,
                                              StructureType[] lineTypes) {
        // 1. 空行
        if (line.isBlank()) {
            return StructureType.BLANK;
        }

        // 2. ATX 标题
        if (ATX_HEADING.matcher(line).matches()) {
            return StructureType.HEADING;
        }

        // 2.5 中文章节标题（第X章/节/篇/卷 + 标题，优先于列表检测）
        if (CHAPTER_HEADING.matcher(line.strip()).matches()) {
            return StructureType.HEADING;
        }

        // 3. 表格行
        if (isTableLine(line, lineIndex, allLines)) {
            return StructureType.TABLE;
        }

        // 4. 列表项
        if (LIST_MARKER.matcher(line).find()) {
            return StructureType.LIST;
        }

        // 5. 缩进代码块（非列表续行）
        if (isIndentedCode(line, lineIndex, lineTypes)) {
            return StructureType.CODE;
        }

        // 6. 纯文本标题启发式（需要上下文辅助判断）
        if (isPlainTextHeading(line, lineIndex, lineTypes)) {
            return StructureType.HEADING;
        }

        // 7. 段落
        return StructureType.PARAGRAPH;
    }

    /**
     * 判断是否为围栏代码块的边界行。
     */
    private boolean isFenceBoundary(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    /**
     * 判断是否为表格行（包含 | 且相邻有分隔行）。
     */
    private boolean isTableLine(String line, int lineIndex, String[] allLines) {
        if (!line.contains("|")) {
            return false;
        }
        // 检查当前行或相邻行是否为分隔行
        if (TABLE_SEPARATOR.matcher(line).matches()) {
            return true;
        }
        if (lineIndex > 0 && TABLE_SEPARATOR.matcher(allLines[lineIndex - 1]).matches()) {
            return true;
        }
        if (lineIndex < allLines.length - 1 && TABLE_SEPARATOR.matcher(allLines[lineIndex + 1]).matches()) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否为缩进代码块（4+ 空格或 tab 缩进，且前一非空行不是列表项）。
     *
     * <p>排除中文段首缩进：包含 CJK 字符的缩进行视为段落文本而非代码。</p>
     */
    private boolean isIndentedCode(String line, int lineIndex, StructureType[] lineTypes) {
        // 检查是否有足够缩进
        int indent = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ' ') {
                indent++;
            } else if (line.charAt(i) == '\t') {
                indent += 4;
            } else {
                break;
            }
        }
        if (indent < minCodeIndent) {
            return false;
        }
        // 包含 CJK 字符的缩进行是中文段首缩进，不是代码
        if (containsCjk(line)) {
            return false;
        }
        // 如果前一个非空行是列表项，则可能是列表续行而非代码
        for (int i = lineIndex - 1; i >= 0; i--) {
            if (lineTypes[i] == StructureType.BLANK) continue;
            return lineTypes[i] != StructureType.LIST;
        }
        return true;
    }

    /** 判断文本是否包含 CJK 统一汉字。 */
    private boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                return true;
            }
        }
        return false;
    }

    /**
     * 纯文本标题启发式 — 综合短行长度、标点、上下文判断是否为章节标题。
     *
     * <p>排除条件（满足任一则不是标题）：
     * <ul>
     *   <li>以句末标点结尾（。！？；.!?; 以及中文冒号 ：:）</li>
     *   <li>包含句内逗号（，,）— 说明是完整句子而非短标题</li>
     *   <li>是列表项</li>
     *   <li>前一行不是空行、标题或文档开头 — 标题应出现在段落分隔之后</li>
     * </ul>
     */
    private boolean isPlainTextHeading(String line, int lineIndex, StructureType[] lineTypes) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.length() >= maxHeadingLength) {
            return false;
        }
        // 以引号/括号开头的行是对话或引用内容，不是标题
        char firstChar = trimmed.charAt(0);
        if (DIALOGUE_OPENERS.indexOf(firstChar) >= 0) {
            return false;
        }
        // 不以句末标点或冒号结尾（冒号表示引导内容，如 "千问系列旗舰模型："）
        char lastChar = trimmed.charAt(trimmed.length() - 1);
        if (SENTENCE_ENDINGS.indexOf(lastChar) >= 0 || lastChar == '：' || lastChar == ':') {
            return false;
        }
        // 不包含中文逗号（包含逗号的行是句子，不是标题）
        if (trimmed.contains("，") || trimmed.contains(",")) {
            return false;
        }
        // 包含 Markdown 链接语法的行不是标题（如 "[下载地址](https://...)"）
        if (trimmed.contains("](")) {
            return false;
        }
        // 包含括号说明的行不是标题（如 "前置条件：Java 22+（下载地址）"）
        if (trimmed.contains("（") && trimmed.contains("）")) {
            return false;
        }
        // 不是列表项
        if (LIST_MARKER.matcher(trimmed).find()) {
            return false;
        }
        // 上下文检查：标题应出现在段落分隔之后（空行、其他标题、或文档开头）
        if (lineIndex > 0) {
            StructureType prevType = lineTypes[lineIndex - 1];
            if (prevType != null && prevType != StructureType.BLANK && prevType != StructureType.HEADING) {
                return false;
            }
        }
        return true;
    }

    // ---- 区域合并 ----

    /**
     * 将标注行合并为连续同类型的结构区域，同时维护标题层级堆栈。
     */
    private List<StructureRegion> groupIntoRegions(AnnotatedLine[] lines, String originalText) {
        if (lines.length == 0) return List.of();

        var regions = new ArrayList<StructureRegion>();
        var headingStack = new ArrayDeque<String>();
        var currentLines = new ArrayList<AnnotatedLine>();
        StructureType currentType = lines[0].type();

        for (var line : lines) {
            // BLANK 行始终独立成区域
            boolean isSeparator = line.type() == StructureType.BLANK;
            boolean typeChanged = line.type() != currentType;

            if ((typeChanged || isSeparator) && !currentLines.isEmpty()) {
                // 结束当前区域
                regions.add(buildRegion(currentLines, originalText, headingStack));
                currentLines.clear();
            }

            // 维护标题层级堆栈
            if (line.type() == StructureType.HEADING) {
                updateHeadingStack(headingStack, line.rawText());
            }

            currentLines.add(line);
            currentType = line.type();

            // BLANK 行立即结束区域
            if (isSeparator) {
                regions.add(buildRegion(currentLines, originalText, headingStack));
                currentLines.clear();
            }
        }

        // 处理最后一组
        if (!currentLines.isEmpty()) {
            regions.add(buildRegion(currentLines, originalText, headingStack));
        }

        return List.copyOf(regions);
    }

    /**
     * 从一组同类型标注行构建结构区域。
     */
    private StructureRegion buildRegion(List<AnnotatedLine> lines, String originalText,
                                         Deque<String> headingStack) {
        var first = lines.getFirst();
        var last = lines.getLast();
        int startOffset = first.startOffset();
        int endOffset = last.endOffset();
        // 从原始文本提取区域内容（确保保留原始格式）
        String content = originalText.substring(startOffset, Math.min(endOffset, originalText.length()));

        return new StructureRegion(
                first.type(),
                startOffset,
                endOffset,
                content,
                List.copyOf(lines),
                List.copyOf(headingStack)
        );
    }

    /**
     * 更新标题层级堆栈 — 遇到新标题时弹出同级或低级标题。
     */
    private void updateHeadingStack(Deque<String> stack, String headingLine) {
        String trimmed = headingLine.strip();
        int level = 0;
        // 解析 ATX 标题级别
        if (trimmed.startsWith("#")) {
            for (int i = 0; i < trimmed.length() && trimmed.charAt(i) == '#'; i++) {
                level++;
            }
        }
        // 非 ATX 标题（纯文本启发式），视为最高级别
        if (level == 0) {
            level = 1;
        }

        // 提取标题文本（去掉 # 前缀）
        String headingText = trimmed.replaceFirst("^#{1,6}\\s+", "").strip();

        // 弹出同级或低级标题（栈底到栈顶是高级→低级）
        // 栈中条目格式为 "level:text"
        while (!stack.isEmpty()) {
            String top = stack.peekLast();
            int topLevel = parseHeadingLevel(top);
            if (topLevel >= level) {
                stack.pollLast();
            } else {
                break;
            }
        }
        stack.addLast(level + ":" + headingText);
    }

    /**
     * 从栈条目解析标题级别。
     */
    private int parseHeadingLevel(String entry) {
        int colonIdx = entry.indexOf(':');
        if (colonIdx > 0) {
            try {
                return Integer.parseInt(entry.substring(0, colonIdx));
            } catch (NumberFormatException ignored) {
                // 解析失败视为顶级
            }
        }
        return 1;
    }
}
