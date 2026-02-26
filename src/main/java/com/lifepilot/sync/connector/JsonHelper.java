package com.lifepilot.sync.connector;

import java.util.*;

/**
 * 简易 JSON 解析/生成工具类，供各连接器共用。
 *
 * <p>不依赖外部 JSON 库，仅处理连接器所需的简单 JSON 结构。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class JsonHelper {

    private JsonHelper() {
        // 工具类，禁止实例化
    }

    /**
     * 简易 JSON 解析：将 {"key":"value",...} 转为 Map&lt;String, String&gt;。
     *
     * @param json JSON 字符串
     * @return 键值对映射
     */
    public static Map<String, String> parseSimpleJsonToMap(String json) {
        var map = new LinkedHashMap<String, String>();
        if (json == null || json.isBlank()) return map;

        var trimmed = json.strip();
        if (trimmed.startsWith("{")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("}")) trimmed = trimmed.substring(0, trimmed.length() - 1);

        int i = 0;
        while (i < trimmed.length()) {
            while (i < trimmed.length() && (trimmed.charAt(i) == ',' || Character.isWhitespace(trimmed.charAt(i)))) i++;
            if (i >= trimmed.length()) break;

            var key = parseJsonString(trimmed, i);
            if (key == null) break;
            i = key.endIndex;

            while (i < trimmed.length() && (trimmed.charAt(i) == ':' || Character.isWhitespace(trimmed.charAt(i)))) i++;

            var value = parseJsonString(trimmed, i);
            if (value == null) break;
            i = value.endIndex;

            map.put(key.value, value.value);
        }
        return map;
    }

    /**
     * 从 JSON 字符串中提取指定 key 的字符串值。
     *
     * @param json JSON 字符串
     * @param key  要提取的键名
     * @return 值字符串，未找到时返回 null
     */
    public static String extractJsonStringValue(String json, String key) {
        var searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) return null;

        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length()) return null;

        if (json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) return null;
            return json.substring(valueStart + 1, valueEnd);
        }

        int valueEnd = valueStart;
        while (valueEnd < json.length() && json.charAt(valueEnd) != ','
                && json.charAt(valueEnd) != '}' && json.charAt(valueEnd) != ']') {
            valueEnd++;
        }
        return json.substring(valueStart, valueEnd).trim();
    }

    /**
     * 从 JSON 中提取指定 key 的数组内容（返回各元素的 JSON 字符串列表）。
     *
     * @param json JSON 字符串
     * @param key  数组键名
     * @return 各元素的 JSON 字符串列表
     */
    public static List<String> extractJsonArray(String json, String key) {
        int bracketStart;
        if (key == null || key.isEmpty()) {
            // 直接查找第一个 '['
            bracketStart = json.indexOf('[');
        } else {
            var searchKey = "\"" + key + "\"";
            int keyIndex = json.indexOf(searchKey);
            if (keyIndex < 0) return List.of();
            bracketStart = json.indexOf('[', keyIndex);
        }
        if (bracketStart < 0) return List.of();

        int depth = 0;
        int bracketEnd = -1;
        for (int i = bracketStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    bracketEnd = i;
                    break;
                }
            }
        }
        if (bracketEnd < 0) return List.of();

        var arrayContent = json.substring(bracketStart + 1, bracketEnd).trim();
        if (arrayContent.isEmpty()) return List.of();

        return splitJsonObjects(arrayContent);
    }

    /**
     * 简易解析 JSON 对象为 Map&lt;String, Object&gt;。
     *
     * @param json JSON 对象字符串
     * @return 键值对映射
     */
    public static Map<String, Object> parseSimpleJsonObject(String json) {
        var map = new LinkedHashMap<String, Object>();
        if (json == null || json.isBlank()) return map;

        var trimmed = json.strip();
        if (trimmed.startsWith("{")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("}")) trimmed = trimmed.substring(0, trimmed.length() - 1);

        int i = 0;
        while (i < trimmed.length()) {
            while (i < trimmed.length() && (trimmed.charAt(i) == ',' || Character.isWhitespace(trimmed.charAt(i)))) i++;
            if (i >= trimmed.length()) break;

            if (trimmed.charAt(i) != '"') break;
            int keyEnd = trimmed.indexOf('"', i + 1);
            if (keyEnd < 0) break;
            var key = trimmed.substring(i + 1, keyEnd);
            i = keyEnd + 1;

            while (i < trimmed.length() && (trimmed.charAt(i) == ':' || Character.isWhitespace(trimmed.charAt(i)))) i++;
            if (i >= trimmed.length()) break;

            char vc = trimmed.charAt(i);
            if (vc == '"') {
                int valueEnd = trimmed.indexOf('"', i + 1);
                if (valueEnd < 0) break;
                map.put(key, trimmed.substring(i + 1, valueEnd));
                i = valueEnd + 1;
            } else if (vc == '{') {
                int depth = 0;
                int objEnd = i;
                for (; objEnd < trimmed.length(); objEnd++) {
                    if (trimmed.charAt(objEnd) == '{') depth++;
                    else if (trimmed.charAt(objEnd) == '}') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                map.put(key, parseSimpleJsonObject(trimmed.substring(i, objEnd + 1)));
                i = objEnd + 1;
            } else if (vc == '[') {
                int depth = 0;
                int arrEnd = i;
                for (; arrEnd < trimmed.length(); arrEnd++) {
                    if (trimmed.charAt(arrEnd) == '[') depth++;
                    else if (trimmed.charAt(arrEnd) == ']') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                var arrayStr = trimmed.substring(i + 1, arrEnd).trim();
                var items = new ArrayList<String>();
                for (var part : arrayStr.split(",")) {
                    var item = part.strip();
                    if (item.startsWith("\"") && item.endsWith("\"")) {
                        items.add(item.substring(1, item.length() - 1));
                    } else if (!item.isEmpty()) {
                        items.add(item);
                    }
                }
                map.put(key, items);
                i = arrEnd + 1;
            } else {
                int valueEnd = i;
                while (valueEnd < trimmed.length() && trimmed.charAt(valueEnd) != ','
                        && trimmed.charAt(valueEnd) != '}' && !Character.isWhitespace(trimmed.charAt(valueEnd))) {
                    valueEnd++;
                }
                var rawValue = trimmed.substring(i, valueEnd);
                if ("true".equals(rawValue) || "false".equals(rawValue)) {
                    map.put(key, Boolean.parseBoolean(rawValue));
                } else if (!"null".equals(rawValue)) {
                    try {
                        map.put(key, Integer.parseInt(rawValue));
                    } catch (NumberFormatException e) {
                        map.put(key, rawValue);
                    }
                }
                i = valueEnd;
            }
        }
        return map;
    }

    /**
     * 将 Map 转换为 JSON 字符串。
     *
     * @param map 键值对映射
     * @return JSON 字符串
     */
    public static String mapToJson(Map<?, ?> map) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            appendJsonValue(sb, entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 将 payload 对象转换为 JSON 字符串。
     *
     * @param payload 数据载荷
     * @return JSON 字符串
     */
    public static String payloadToJson(Object payload) {
        if (payload instanceof String s) return s;
        if (payload instanceof Map<?, ?> map) return mapToJson(map);
        return "{}";
    }

    /**
     * JSON 字符串转义。
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    public static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== 内部辅助方法 ====================

    private static void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append("\"").append(escapeJson(s)).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof List<?> list) {
            sb.append("[");
            boolean listFirst = true;
            for (var item : list) {
                if (!listFirst) sb.append(",");
                listFirst = false;
                appendJsonValue(sb, item);
            }
            sb.append("]");
        } else if (value instanceof Map<?, ?> nested) {
            sb.append(mapToJson(nested));
        } else {
            sb.append("\"").append(escapeJson(value.toString())).append("\"");
        }
    }

    private record JsonToken(String value, int endIndex) {}

    private static JsonToken parseJsonString(String s, int start) {
        if (start >= s.length()) return null;
        if (s.charAt(start) == '"') {
            int end = s.indexOf('"', start + 1);
            if (end < 0) return null;
            return new JsonToken(s.substring(start + 1, end), end + 1);
        }
        int end = start;
        while (end < s.length() && s.charAt(end) != ',' && s.charAt(end) != '}'
                && !Character.isWhitespace(s.charAt(end))) {
            end++;
        }
        return new JsonToken(s.substring(start, end), end);
    }

    private static List<String> splitJsonObjects(String content) {
        var objects = new ArrayList<String>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(content.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }
}
