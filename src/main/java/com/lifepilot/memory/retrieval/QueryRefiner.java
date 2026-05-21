package com.lifepilot.memory.retrieval;

import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 查询精炼器 — 对用户原始输入进行清洗和截断，提升检索召回质量。
 *
 * @author zsg
 * @since 2026-03-12
 */
public class QueryRefiner {

    private static final Logger log = LoggerFactory.getLogger(QueryRefiner.class);

    /** 中文口语填充词正则：嗯、啊、呃、那个、就是说、然后呢、对吧、你知道吗 等。 */
    private static final Pattern FILLER_PATTERN = Pattern.compile(
            "(嗯+|啊+|呃+|额+|哦+|那个|就是说|然后呢|对吧|你知道吗|怎么说呢|反正就是|我觉得吧)");

    /** 代码特征正则：检测常见编程语言关键字和语法结构。 */
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "(\\bdef\\s+\\w+|\\bfunction\\s+\\w+|\\bclass\\s+\\w+|\\bimport\\s+|\\bfrom\\s+\\w+\\s+import" +
            "|\\bconst\\s+|\\blet\\s+|\\bvar\\s+|\\breturn\\s+|\\bif\\s*\\(|\\bfor\\s*\\(" +
            "|\\bwhile\\s*\\(|=>|\\{\\s*\\}|\\[\\s*\\]|\\w+\\.\\w+\\(|\\w+\\(\\)" +
            "|public\\s+|private\\s+|static\\s+|void\\s+|int\\s+|String\\s+" +
            "|System\\.out|println|printf|console\\.log|print\\()");

    private final MemoryRetrievalProperties properties;

    public QueryRefiner(MemoryRetrievalProperties properties) {
        this.properties = properties;
    }

    /**
     * 精炼查询文本。
     *
     * @param rawInput 用户原始输入
     * @return 精炼后的查询文本
     */
    public String refine(String rawInput) {
        // null / 空白 → 空字符串
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }

        String trimmed = rawInput.trim();

        // 短于 queryMinLength → 直接返回原文
        if (trimmed.length() < properties.getQueryMinLength()) {
            return trimmed;
        }

        // 检测代码片段：如果输入主要是代码，提取自然语言部分
        if (isCodeDominated(trimmed)) {
            String naturalPart = extractNaturalLanguage(trimmed);
            if (!naturalPart.isBlank()) {
                log.debug("查询精炼: 检测到代码片段，提取自然语言部分, raw={}, extracted={}",
                        truncateForLog(trimmed, 50), naturalPart);
                trimmed = naturalPart;
            } else {
                log.debug("查询精炼: 检测到纯代码片段，无自然语言可提取, raw={}",
                        truncateForLog(trimmed, 50));
                return trimmed;
            }
        }

        // 移除中文口语填充词
        String refined = FILLER_PATTERN.matcher(trimmed).replaceAll("").trim();

        // 精炼后为空 → 兜底返回原文
        if (refined.isEmpty()) {
            log.debug("查询精炼: 精炼后为空，兜底返回原文, rawInput={}", trimmed);
            return trimmed;
        }

        // 超过 queryMaxLength → 截断
        int maxLen = properties.getQueryMaxLength();
        if (refined.length() > maxLen) {
            refined = refined.substring(0, maxLen);
            log.debug("查询精炼: 截断至 {} 字符", maxLen);
        }

        return refined;
    }

    // ========== 内部方法 ==========

    /**
     * 判断输入是否以代码为主（代码特征匹配数 ≥ 2 且代码行占比 > 50%）。
     */
    private boolean isCodeDominated(String text) {
        var matcher = CODE_PATTERN.matcher(text);
        int matchCount = 0;
        while (matcher.find()) {
            matchCount++;
            if (matchCount >= 2) break;
        }
        if (matchCount < 2) return false;

        // 代码行占比检查：含缩进或特殊符号的行视为代码行
        String[] lines = text.split("\n");
        int codeLines = 0;
        for (String line : lines) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("//") || stripped.startsWith("#")
                    || stripped.startsWith("import ") || stripped.startsWith("from ")
                    || stripped.contains("(") || stripped.contains("{")
                    || stripped.contains("=") || stripped.contains(";")
                    || line.length() - stripped.length() >= 4) {
                codeLines++;
            }
        }
        return lines.length > 0 && (float) codeLines / lines.length > 0.5f;
    }

    /**
     * 从混合文本中提取自然语言部分（中文句子或非代码行）。
     */
    private String extractNaturalLanguage(String text) {
        var sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty()) continue;
            // 保留中文为主的行（中文字符占比 > 30%）
            long cjkCount = stripped.chars()
                    .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                    .count();
            if (cjkCount > 0 && (float) cjkCount / stripped.length() > 0.3f) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(stripped);
            }
        }
        return sb.toString().trim();
    }

    /** 截断文本用于日志输出。 */
    private String truncateForLog(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
