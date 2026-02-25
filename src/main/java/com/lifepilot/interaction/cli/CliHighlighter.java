package com.lifepilot.interaction.cli;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * CLI 语法高亮器 — 对命令关键字和参数进行语法高亮。
 *
 * <p>高亮规则：
 * <ul>
 *   <li>顶层命令（chat/todo/schedule/habit/llm/mcp/skill）→ 粗体 + 青色</li>
 *   <li>特殊命令（/exit、/quit、/new）→ 粗体 + 黄色</li>
 *   <li>子命令（list/add/test/info）→ 绿色</li>
 *   <li>其他内容（参数）→ 默认样式</li>
 * </ul>
 *
 * <p>解析逻辑：按空白字符分割输入，第一个词为命令，第二个词为子命令，其余为参数。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class CliHighlighter implements Highlighter {

    /** 顶层命令集合。 */
    private static final Set<String> TOP_COMMANDS = Set.of(
            "chat", "todo", "schedule", "habit", "llm", "mcp", "skill"
    );

    /** 特殊命令集合。 */
    private static final Set<String> SPECIAL_COMMANDS = Set.of(
            "/exit", "/quit", "/new"
    );

    /** 子命令集合。 */
    private static final Set<String> SUB_COMMANDS = Set.of(
            "list", "add", "test", "info"
    );

    /** 顶层命令样式：粗体 + 青色。 */
    private static final AttributedStyle TOP_COMMAND_STYLE =
            AttributedStyle.BOLD.foreground(AttributedStyle.CYAN);

    /** 特殊命令样式：粗体 + 黄色。 */
    private static final AttributedStyle SPECIAL_COMMAND_STYLE =
            AttributedStyle.BOLD.foreground(AttributedStyle.YELLOW);

    /** 子命令样式：绿色。 */
    private static final AttributedStyle SUB_COMMAND_STYLE =
            AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return new AttributedString(buffer == null ? "" : buffer);
        }

        var builder = new AttributedStringBuilder();
        // 按空白字符分割，保留分隔符位置以精确重建原始字符串
        int pos = 0;
        int wordIndex = 0;

        while (pos < buffer.length()) {
            // 跳过前导空白，保留原始空白字符
            int wsStart = pos;
            while (pos < buffer.length() && Character.isWhitespace(buffer.charAt(pos))) {
                pos++;
            }
            if (pos > wsStart) {
                builder.append(buffer.substring(wsStart, pos));
            }

            if (pos >= buffer.length()) {
                break;
            }

            // 提取一个词
            int wordStart = pos;
            while (pos < buffer.length() && !Character.isWhitespace(buffer.charAt(pos))) {
                pos++;
            }
            String word = buffer.substring(wordStart, pos);

            // 根据词的位置和内容决定样式
            AttributedStyle style = resolveStyle(word, wordIndex);
            builder.styled(style, word);
            wordIndex++;
        }

        return builder.toAttributedString();
    }

    @Override
    public void setErrorPattern(Pattern errorPattern) {
        // 不需要错误模式高亮
    }

    @Override
    public void setErrorIndex(int errorIndex) {
        // 不需要错误索引高亮
    }

    /**
     * 根据词内容和位置解析高亮样式。
     *
     * @param word      当前词
     * @param wordIndex 词在输入中的位置索引（从 0 开始）
     * @return 对应的高亮样式
     */
    private AttributedStyle resolveStyle(String word, int wordIndex) {
        if (wordIndex == 0) {
            // 第一个词：判断是顶层命令还是特殊命令
            if (TOP_COMMANDS.contains(word)) {
                return TOP_COMMAND_STYLE;
            }
            if (SPECIAL_COMMANDS.contains(word)) {
                return SPECIAL_COMMAND_STYLE;
            }
            return AttributedStyle.DEFAULT;
        }

        if (wordIndex == 1 && SUB_COMMANDS.contains(word)) {
            // 第二个词且为已知子命令
            return SUB_COMMAND_STYLE;
        }

        // 其他位置：默认样式（参数）
        return AttributedStyle.DEFAULT;
    }
}
