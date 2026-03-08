package com.lifepilot.sync.connector.caldav;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 5545 iCalendar 格式解析器，支持 VEVENT 和 VTODO 组件的解析与生成。
 *
 * <p>处理多行折叠（RFC 5545 §3.1）和特殊字符转义。
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class ICalendarParser {

    /** RFC 5545 规定的行最大字节数（不含 CRLF）。 */
    private static final int MAX_LINE_OCTETS = 75;

    /** CRLF 行终止符。 */
    private static final String CRLF = "\r\n";

    private ICalendarParser() {
        // 工具类，禁止实例化
    }

    // ==================== 解析方法 ====================

    /**
     * 解析 iCalendar 文本，提取第一个 VEVENT 或 VTODO 组件的属性。
     *
     * @param icalText iCalendar 格式文本
     * @return 属性名 → 属性值的映射；如果未找到组件则返回空 Map
     */
    public static Map<String, String> parseComponent(String icalText) {
        if (icalText == null || icalText.isBlank()) {
            return Map.of();
        }

        // 先展开多行折叠
        var unfolded = unfold(icalText);
        var lines = unfolded.split("\\r?\\n");

        var properties = new LinkedHashMap<String, String>();
        boolean insideComponent = false;
        String componentType = null;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }

            // 检测组件开始
            if (!insideComponent) {
                if ("BEGIN:VEVENT".equalsIgnoreCase(line.trim())) {
                    insideComponent = true;
                    componentType = "VEVENT";
                    continue;
                } else if ("BEGIN:VTODO".equalsIgnoreCase(line.trim())) {
                    insideComponent = true;
                    componentType = "VTODO";
                    continue;
                }
                continue;
            }

            // 检测组件结束
            var trimmed = line.trim();
            if (("END:VEVENT".equalsIgnoreCase(trimmed) && "VEVENT".equals(componentType))
                    || ("END:VTODO".equalsIgnoreCase(trimmed) && "VTODO".equals(componentType))) {
                // 记录组件类型
                properties.put("X-COMPONENT-TYPE", componentType);
                break;
            }

            // 解析属性行：NAME;PARAM=VALUE:PROPERTY_VALUE 或 NAME:PROPERTY_VALUE
            int colonIndex = findPropertyValueSeparator(line);
            if (colonIndex < 0) {
                continue;
            }

            var nameWithParams = line.substring(0, colonIndex);
            var value = line.substring(colonIndex + 1);

            // 提取纯属性名（去掉参数部分）
            var propertyName = extractPropertyName(nameWithParams);

            // 反转义属性值
            properties.put(propertyName, unescapeValue(value));
        }

        return Map.copyOf(properties);
    }

    // ==================== 格式化方法 ====================

    /**
     * 将属性映射格式化为 VEVENT iCalendar 文本。
     *
     * @param properties 属性名 → 属性值的映射
     * @return 完整的 iCalendar VCALENDAR 文本（包含 VEVENT 组件）
     */
    public static String formatVEvent(Map<String, String> properties) {
        return formatComponent("VEVENT", properties);
    }

    /**
     * 将属性映射格式化为 VTODO iCalendar 文本。
     *
     * @param properties 属性名 → 属性值的映射
     * @return 完整的 iCalendar VCALENDAR 文本（包含 VTODO 组件）
     */
    public static String formatVTodo(Map<String, String> properties) {
        return formatComponent("VTODO", properties);
    }

    // ==================== 折叠/展开方法 ====================

    /**
     * 展开多行折叠内容（RFC 5545 §3.1）。
     *
     * <p>以空格或制表符开头的行是前一行的延续，需要合并。
     *
     * @param icalText 可能包含折叠行的 iCalendar 文本
     * @return 展开后的文本
     */
    public static String unfold(String icalText) {
        if (icalText == null || icalText.isEmpty()) {
            return "";
        }
        // RFC 5545: 折叠是 CRLF 后跟一个空格或制表符
        // 同时兼容 LF-only 的情况
        return icalText
                .replace("\r\n ", "")
                .replace("\r\n\t", "")
                .replace("\n ", "")
                .replace("\n\t", "");
    }

    /**
     * 对长行进行折叠（RFC 5545 §3.1）。
     *
     * <p>超过 75 字节的行在适当位置插入 CRLF + 空格。
     *
     * @param line 单行文本（不含行终止符）
     * @return 折叠后的文本（可能包含 CRLF + 空格）
     */
    public static String fold(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }

        var bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_LINE_OCTETS) {
            return line;
        }

        var sb = new StringBuilder();
        int currentLineStart = 0;
        // 第一行最多 75 字节，后续行最多 74 字节（因为前导空格占 1 字节）
        int maxOctets = MAX_LINE_OCTETS;

        while (currentLineStart < bytes.length) {
            int remaining = bytes.length - currentLineStart;
            int chunkSize = Math.min(remaining, maxOctets);

            // 确保不在多字节 UTF-8 字符中间截断
            chunkSize = adjustForUtf8Boundary(bytes, currentLineStart, chunkSize);

            var chunk = new String(bytes, currentLineStart, chunkSize, StandardCharsets.UTF_8);

            if (currentLineStart > 0) {
                sb.append(CRLF).append(' ');
            }
            sb.append(chunk);

            currentLineStart += chunkSize;
            // 后续行前导空格占 1 字节，所以内容最多 74 字节
            maxOctets = MAX_LINE_OCTETS - 1;
        }

        return sb.toString();
    }

    // ==================== 转义/反转义方法 ====================

    /**
     * 转义 iCalendar 属性值中的特殊字符。
     *
     * <p>RFC 5545 转义规则：
     * <ul>
     *   <li>反斜杠 {@code \} → {@code \\}</li>
     *   <li>换行符 → {@code \n}</li>
     *   <li>逗号 {@code ,} → {@code \,}</li>
     *   <li>分号 {@code ;} → {@code \;}</li>
     * </ul>
     *
     * @param value 原始属性值
     * @return 转义后的属性值
     */
    public static String escapeValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("\\", "\\\\")    // 反斜杠必须最先转义
                .replace("\n", "\\n")
                .replace("\r", "")         // 去除 CR，只保留 \n 转义
                .replace(",", "\\,")
                .replace(";", "\\;");
    }

    /**
     * 反转义 iCalendar 属性值中的转义字符。
     *
     * @param value 转义后的属性值
     * @return 原始属性值
     */
    public static String unescapeValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                switch (next) {
                    case 'n', 'N' -> {
                        sb.append('\n');
                        i++;
                    }
                    case '\\' -> {
                        sb.append('\\');
                        i++;
                    }
                    case ',' -> {
                        sb.append(',');
                        i++;
                    }
                    case ';' -> {
                        sb.append(';');
                        i++;
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 格式化组件为完整的 iCalendar 文本。
     */
    private static String formatComponent(String componentType, Map<String, String> properties) {
        var sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR").append(CRLF);
        sb.append("VERSION:2.0").append(CRLF);
        sb.append("PRODID:-//ZhiWei//NONSGML v1.0//EN").append(CRLF);
        sb.append("BEGIN:").append(componentType).append(CRLF);

        for (var entry : properties.entrySet()) {
            var name = entry.getKey();
            // 跳过内部标记属性
            if ("X-COMPONENT-TYPE".equals(name)) {
                continue;
            }
            var escapedValue = escapeValue(entry.getValue());
            var propertyLine = name + ":" + escapedValue;
            sb.append(fold(propertyLine)).append(CRLF);
        }

        sb.append("END:").append(componentType).append(CRLF);
        sb.append("END:VCALENDAR").append(CRLF);
        return sb.toString();
    }

    /**
     * 查找属性值分隔符（冒号）的位置，跳过参数中的冒号。
     *
     * <p>属性行格式：{@code NAME;PARAM=VALUE:PROPERTY_VALUE}
     * 需要找到第一个不在引号内的冒号。
     */
    private static int findPropertyValueSeparator(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ':' && !inQuotes) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 从属性名（可能包含参数）中提取纯属性名。
     *
     * <p>例如 {@code DTSTART;VALUE=DATE} → {@code DTSTART}
     */
    private static String extractPropertyName(String nameWithParams) {
        int semicolonIndex = nameWithParams.indexOf(';');
        if (semicolonIndex >= 0) {
            return nameWithParams.substring(0, semicolonIndex).toUpperCase();
        }
        return nameWithParams.toUpperCase();
    }

    /**
     * 调整截断位置以避免在多字节 UTF-8 字符中间截断。
     */
    private static int adjustForUtf8Boundary(byte[] bytes, int start, int chunkSize) {
        int end = start + chunkSize;
        if (end >= bytes.length) {
            return chunkSize;
        }
        // 如果截断位置落在多字节字符的后续字节上（10xxxxxx），向前回退
        while (chunkSize > 0 && (bytes[start + chunkSize] & 0xC0) == 0x80) {
            chunkSize--;
        }
        return chunkSize;
    }
}
