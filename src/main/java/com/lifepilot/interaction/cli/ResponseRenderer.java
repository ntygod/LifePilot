package com.lifepilot.interaction.cli;

import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.List;

/**
 * 响应渲染器 — 格式化输出到终端。
 *
 * <p>提供成功/失败/表格/流式等多种输出格式，
 * 统一 CLI 交互层的输出风格。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ResponseRenderer {

    private final PrintWriter writer;

    /**
     * 创建响应渲染器。
     *
     * @param terminal JLine 终端实例
     */
    public ResponseRenderer(Terminal terminal) {
        this.writer = terminal.writer();
    }

    /**
     * 仅用于测试的构造函数。
     *
     * @param writer 输出写入器
     */
    ResponseRenderer(PrintWriter writer) {
        this.writer = writer;
    }

    /** 输出成功信息（✅ 前缀）。 */
    public void success(String message) {
        writer.println("✅ " + message);
        writer.flush();
    }

    /** 输出错误信息（❌ 前缀）。 */
    public void error(String message) {
        writer.println("❌ " + message);
        writer.flush();
    }

    /** 输出普通信息。 */
    public void info(String message) {
        writer.println(message);
        writer.flush();
    }

    /**
     * 输出表格数据。
     *
     * @param headers 表头列表
     * @param rows 数据行列表
     */
    public void table(List<String> headers, List<List<String>> rows) {
        if (headers.isEmpty()) return;

        // 计算每列最大宽度
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = displayWidth(headers.get(i));
        }
        for (List<String> row : rows) {
            for (int i = 0; i < Math.min(cols, row.size()); i++) {
                widths[i] = Math.max(widths[i], displayWidth(row.get(i)));
            }
        }

        // 输出表头
        StringBuilder headerLine = new StringBuilder();
        StringBuilder separator = new StringBuilder();
        for (int i = 0; i < cols; i++) {
            if (i > 0) {
                headerLine.append("  ");
                separator.append("  ");
            }
            headerLine.append(padRight(headers.get(i), widths[i]));
            separator.append("-".repeat(widths[i]));
        }
        writer.println(headerLine);
        writer.println(separator);

        // 输出数据行
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < cols; i++) {
                if (i > 0) line.append("  ");
                String cell = i < row.size() ? row.get(i) : "";
                line.append(padRight(cell, widths[i]));
            }
            writer.println(line);
        }
        writer.flush();
    }

    /** 流式输出（逐 token）。 */
    public void streamToken(String token) {
        writer.print(token);
        writer.flush();
    }

    /** 流式输出结束（换行）。 */
    public void streamEnd() {
        writer.println();
        writer.flush();
    }

    /**
     * 计算字符串显示宽度（中文字符占 2 列）。
     */
    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // CJK 统一汉字及扩展区域占 2 列
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
                width += 2;
            } else {
                width += 1;
            }
        }
        return width;
    }

    /**
     * 右填充字符串到指定显示宽度。
     */
    private static String padRight(String s, int targetWidth) {
        int currentWidth = displayWidth(s);
        if (currentWidth >= targetWidth) return s;
        return s + " ".repeat(targetWidth - currentWidth);
    }
}
