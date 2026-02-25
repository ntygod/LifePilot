package com.lifepilot.interaction.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多行输入解析器 — 支持反斜杠续行和三引号块两种多行输入模式。
 *
 * <p>当用户输入以 {@code \} 结尾时，进入反斜杠续行模式，
 * 去掉尾部 {@code \} 后继续读取下一行，直到某行不以 {@code \} 结尾。
 * 各行以换行符拼接。</p>
 *
 * <p>当用户输入以 {@code """} 开头时，进入三引号块模式，
 * 读取后续行直到遇到独立的 {@code """}，中间所有行以换行符拼接。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public final class MultiLineParser {

    private static final Logger log = LoggerFactory.getLogger(MultiLineParser.class);

    private static final String TRIPLE_QUOTE = "\"\"\"";

    private MultiLineParser() {
        // 工具类，禁止实例化
    }

    /**
     * 读取多行输入。
     *
     * <p>根据首行内容判断是否进入多行模式：
     * <ul>
     *   <li>以 {@code \} 结尾 → 反斜杠续行模式</li>
     *   <li>以 {@code """} 开头 → 三引号块模式</li>
     *   <li>其他 → 直接返回首行</li>
     * </ul>
     * </p>
     *
     * @param firstLine          已读取的首行
     * @param lineReader         JLine LineReader，用于读取后续行
     * @param continuationPrompt 续行提示符（如 "... "）
     * @return 拼接后的完整消息
     */
    public static String readMultiLine(String firstLine, LineReader lineReader, String continuationPrompt) {
        if (firstLine == null || firstLine.isEmpty()) {
            return firstLine == null ? "" : firstLine;
        }

        // 三引号块模式
        if (firstLine.startsWith(TRIPLE_QUOTE)) {
            return readTripleQuoteBlock(firstLine, lineReader, continuationPrompt);
        }

        // 反斜杠续行模式
        if (firstLine.endsWith("\\")) {
            return readBackslashContinuation(firstLine, lineReader, continuationPrompt);
        }

        // 普通单行，直接返回
        return firstLine;
    }

    /**
     * 反斜杠续行模式：去掉尾部 {@code \}，继续读取直到某行不以 {@code \} 结尾。
     *
     * @param firstLine          首行（以 {@code \} 结尾）
     * @param lineReader         JLine LineReader
     * @param continuationPrompt 续行提示符
     * @return 拼接后的完整消息
     */
    private static String readBackslashContinuation(String firstLine, LineReader lineReader, String continuationPrompt) {
        var sb = new StringBuilder();
        // 去掉首行尾部的反斜杠
        sb.append(firstLine, 0, firstLine.length() - 1);

        while (true) {
            String nextLine;
            try {
                nextLine = lineReader.readLine(continuationPrompt);
            } catch (UserInterruptException | EndOfFileException e) {
                // Ctrl+C 或 Ctrl+D：返回已收集的内容
                log.debug("多行输入中断（反斜杠续行模式）: collected={}", sb.length());
                return sb.toString();
            }

            if (nextLine.endsWith("\\")) {
                // 还有续行，去掉尾部反斜杠，加换行后继续
                sb.append('\n');
                sb.append(nextLine, 0, nextLine.length() - 1);
            } else {
                // 最后一行，拼接后结束
                sb.append('\n');
                sb.append(nextLine);
                break;
            }
        }

        return sb.toString();
    }

    /**
     * 三引号块模式：读取 {@code """} 之后的内容，直到遇到独立的 {@code """}。
     *
     * <p>首行 {@code """} 之后的内容（如果有）作为块的第一行。
     * 结束行 {@code """} 之前的内容（如果有）作为块的最后一行。</p>
     *
     * @param firstLine          首行（以 {@code """} 开头）
     * @param lineReader         JLine LineReader
     * @param continuationPrompt 续行提示符
     * @return 拼接后的完整消息
     */
    private static String readTripleQuoteBlock(String firstLine, LineReader lineReader, String continuationPrompt) {
        var sb = new StringBuilder();

        // 首行去掉开头的 """，取剩余内容
        String afterOpen = firstLine.substring(TRIPLE_QUOTE.length());

        // 如果首行同时包含结束的 """（如 """hello"""），提取中间内容
        if (afterOpen.endsWith(TRIPLE_QUOTE)) {
            return afterOpen.substring(0, afterOpen.length() - TRIPLE_QUOTE.length());
        }

        // 首行 """ 后有内容，作为第一行
        if (!afterOpen.isEmpty()) {
            sb.append(afterOpen);
        }

        // 继续读取直到遇到 """
        while (true) {
            String nextLine;
            try {
                nextLine = lineReader.readLine(continuationPrompt);
            } catch (UserInterruptException | EndOfFileException e) {
                // Ctrl+C 或 Ctrl+D：返回已收集的内容
                log.debug("多行输入中断（三引号块模式）: collected={}", sb.length());
                return sb.toString();
            }

            // 检查是否为结束行
            if (nextLine.equals(TRIPLE_QUOTE)) {
                // 独立的 """，结束
                break;
            }

            if (nextLine.endsWith(TRIPLE_QUOTE)) {
                // 行尾有 """，取 """ 之前的内容作为最后一行
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(nextLine, 0, nextLine.length() - TRIPLE_QUOTE.length());
                break;
            }

            // 普通行，追加
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(nextLine);
        }

        return sb.toString();
    }
}
