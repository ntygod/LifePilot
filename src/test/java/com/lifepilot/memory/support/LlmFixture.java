package com.lifepilot.memory.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

/**
 * 按 scenario 名字加载 {@code llm-fixtures/*.json}，按最后一条用户消息的内容正则匹配命中条目，
 * 并支持 {@code $变量} 占位符上下文注入。
 *
 * <p>Fixture JSON 是数组，每项结构：
 * <pre>
 * {
 *   "when":    { "last_user_contains": "正则或子串" },
 *   "respond": { "tool_calls": [...], "final": "最终文本" }
 * }
 * </pre>
 * {@code when.last_user_contains} 作为 Java 正则；{@code respond.tool_calls} 保存为原始 JSON 字符串，
 * 供调用方解析；{@code respond.final} 是助手最终文本。</p>
 *
 * <p>变量注入：调用 {@link #captureVar(String, String)} 后，匹配到的 {@code tool_calls} JSON 中所有
 * {@code "$key"}（含引号）会被替换为捕获的值（带引号）。典型场景是先从一条 fixture 项取到 memoryId，
 * 再让下一条 fixture 用这个 id。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class LlmFixture {

    private static final ObjectMapper OM = new ObjectMapper();
    private List<FixtureEntry> entries = List.of();
    private final Map<String, String> capturedVars = new HashMap<>();

    /**
     * 加载 classpath 下 {@code llm-fixtures/{scenario}.json}。
     *
     * @param scenario 场景名，不含扩展名
     * @throws IllegalStateException 资源缺失或 JSON 解析失败
     */
    public void load(String scenario) {
        try {
            var resource = new ClassPathResource("llm-fixtures/" + scenario + ".json");
            try (var in = resource.getInputStream()) {
                JsonNode arr = OM.readTree(in);
                var list = new ArrayList<FixtureEntry>();
                for (JsonNode node : arr) {
                    list.add(new FixtureEntry(
                            node.at("/when/last_user_contains").asText(),
                            node.at("/respond/tool_calls").toString(),
                            node.at("/respond/final").asText()
                    ));
                }
                entries = list;
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载 fixture 失败: " + scenario, e);
        }
    }

    /**
     * 按最后一条用户消息匹配命中的 fixture 项，并在 tool_calls JSON 中替换已捕获变量。
     *
     * <p>遍历顺序与 JSON 数组一致；第一个 {@code Pattern.find()} 命中的条目即返回。</p>
     *
     * @param lastUserMessage 最后一条用户消息文本
     * @return 渲染后的 fixture 响应
     * @throws IllegalStateException 没有任何条目命中
     */
    public FixtureResponse matchAndRender(String lastUserMessage) {
        for (var entry : entries) {
            if (Pattern.compile(entry.pattern()).matcher(lastUserMessage).find()) {
                return new FixtureResponse(renderVars(entry.toolCallsJson()), entry.finalText());
            }
        }
        var patterns = entries.stream()
                .map(FixtureEntry::pattern)
                .toList();
        throw new IllegalStateException(
                "fixture 未命中。last_user_message=" + lastUserMessage
                + "；已加载的 patterns=" + patterns);
    }

    /**
     * 捕获变量，供后续 fixture 项的 {@code "$key"} 占位符渲染。
     * 值会通过 Jackson 序列化为 JSON 字面量，所以含引号 / 反斜杠 / 换行等特殊字符的值是安全的。
     *
     * @param key   变量名（不含 {@code $}）
     * @param value 变量值（原始文本，占位符渲染时会被包入引号）
     */
    public void captureVar(String key, String value) {
        capturedVars.put(key, value);
    }

    private String renderVars(String json) {
        var result = json;
        for (var kv : capturedVars.entrySet()) {
            String literal;
            try {
                literal = OM.writeValueAsString(kv.getValue());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("变量 " + kv.getKey() + " 无法序列化为 JSON", e);
            }
            result = result.replace("\"$" + kv.getKey() + "\"", literal);
        }
        return result;
    }

    /**
     * 单条 fixture 条目的内部表示。
     *
     * @param pattern       {@code when.last_user_contains} 原始正则
     * @param toolCallsJson {@code respond.tool_calls} 序列化后的 JSON 字符串
     * @param finalText     {@code respond.final} 最终文本
     */
    private record FixtureEntry(String pattern, String toolCallsJson, String finalText) {
    }

    /**
     * LLM 替身响应。
     *
     * @param toolCallsJson tool_calls 数组 JSON（字符串形态，调用方自行解析）
     * @param finalText     助手最终文本
     */
    public record FixtureResponse(String toolCallsJson, String finalText) {
    }
}
