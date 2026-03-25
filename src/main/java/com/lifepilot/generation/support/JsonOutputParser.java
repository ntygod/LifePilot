package com.lifepilot.generation.support;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 输出解析支持。
 *
 * <p>用于将普通文本生成结果按 JSON 结构修复并反序列化为目标对象，
 * 适合“普通 CHAT 调用 + 本地 JSON 解析”的场景。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public final class JsonOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonOutputParser() {
    }

    public static <T> T parse(String raw, Class<T> responseType) {
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("JSON 解析失败: LLM 返回空内容");
        }
        String repaired = repairJson(raw);
        try {
            return MAPPER.readValue(repaired, responseType);
        } catch (Exception ex) {
            throw new RuntimeException("JSON 修复后仍无法解析: " + ex.getMessage(), ex);
        }
    }

    /**
     * 修复 LLM 返回的常见 JSON 格式问题。
     *
     * <p>处理：字符串值内未转义的双引号、Markdown 代码块包裹、尾部逗号等。</p>
     */
    public static String repairJson(String raw) {
        if (raw == null) return null;
        String text = raw.strip();
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        text = text.strip();

        var sb = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                sb.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (!inString) {
                    inString = true;
                    sb.append(c);
                } else {
                    if (isStringTerminator(text, i)) {
                        inString = false;
                        sb.append(c);
                    } else {
                        sb.append('\\').append(c);
                    }
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isStringTerminator(String text, int i) {
        for (int j = i + 1; j < text.length(); j++) {
            char next = text.charAt(j);
            if (next == ' ' || next == '\t' || next == '\r' || next == '\n') continue;
            return next == ',' || next == ':' || next == ']' || next == '}';
        }
        return true;
    }
}
