package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReasoningContentInjectionRewriter} 请求体改写契约测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>带 marker 的 assistant 消息 → 抽 marker 注入到 reasoning_content 字段</li>
 *   <li>无 marker 的 assistant 消息 → 不改动</li>
 *   <li>非 assistant 消息（user / system / tool） → 不改动</li>
 *   <li>多个 assistant 消息（多轮） → 各自独立改写</li>
 *   <li>marker 段为 content 全部 → 移除 content 字段</li>
 *   <li>非预期 JSON 结构 → 返回 null pass-through</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-28
 */
@DisplayName("ReasoningContentInjectionRewriter 请求体改写")
class ReasoningContentInjectionRewriter_请求体改写测试 {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 完整模式 — DeepSeek 协议 provider 走此模式（注入 reasoning_content + 兜底空串）。 */
    private final ReasoningContentInjectionRewriter rewriter = ReasoningContentInjectionRewriter.FULL_INJECTION;
    /** 清理模式 — 其他 OpenAI 兼容 provider 走此模式（仅清理 marker）。 */
    private final ReasoningContentInjectionRewriter cleanupRewriter = ReasoningContentInjectionRewriter.CLEANUP_ONLY;

    @Test
    void 带_marker_的_assistant_消息抽取_reasoning_注入字段() throws IOException {
        String reasoning = "我需要先调用搜索";
        String content = ReasoningContentMarker.encode(null, reasoning);
        String body = """
                {"messages":[
                  {"role":"user","content":"帮我查"},
                  {"role":"assistant","content":%s,"tool_calls":[{"id":"c1","type":"function","function":{"name":"web_search","arguments":"{}"}}]}
                ]}
                """.formatted(MAPPER.writeValueAsString(content));

        byte[] result = rewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        JsonNode root = MAPPER.readTree(result);
        JsonNode assistant = root.get("messages").get(1);
        assertThat(assistant.get("reasoning_content").asText()).isEqualTo(reasoning);
        // marker 段是 content 全部 → content 字段被移除（不留空串）
        assertThat(assistant.has("content")).isFalse();
        // tool_calls 保持原样
        assertThat(assistant.get("tool_calls").get(0).get("id").asText()).isEqualTo("c1");
    }

    @Test
    void marker_前后含原_content_时只剥离_marker_段() throws IOException {
        String reasoning = "推理";
        // 模拟 marker 前/后还有真实正文（虽然 ProviderMessageBuilder 当前不会这么生成）
        String content = ReasoningContentMarker.encode("说明文本", reasoning);
        String body = """
                {"messages":[
                  {"role":"assistant","content":%s,"tool_calls":[]}
                ]}
                """.formatted(MAPPER.writeValueAsString(content));

        byte[] result = rewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        JsonNode assistant = MAPPER.readTree(result).get("messages").get(0);
        assertThat(assistant.get("reasoning_content").asText()).isEqualTo(reasoning);
        assertThat(assistant.get("content").asText()).isEqualTo("说明文本");
    }

    @Test
    void 不含_marker_且无_tool_calls_的_assistant_消息不被改动() throws IOException {
        String body = """
                {"messages":[
                  {"role":"assistant","content":"普通回答","tool_calls":[]}
                ]}
                """;

        byte[] result = rewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        // 无 tool_calls + 无 marker → DeepSeek 协议不要求 reasoning_content，pass-through
        assertThat(result).isNull();
    }

    @Test
    void 含_tool_calls_无_marker_保持_pass_through() throws IOException {
        // 无 marker = 上游 wiring 没编码（callback 未设置 reasoning，意味着非 thinking
        // 模式，如 GPT-4o 普通响应）。不再做兜底注入 —— 真值链路是唯一通道，
        // marker != null 在 ProviderMessageBuilder 编码（包括 ""），到这里没 marker
        // 即视为非 thinking 调用，不应注入 reasoning_content 字段。
        String body = """
                {"messages":[
                  {"role":"user","content":"q"},
                  {"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]}
                ]}
                """;

        byte[] result = rewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNull();
    }

    @Test
    void 完整模式_空_marker_抽出空_reasoning_注入空字段() throws IOException {
        // DeepSeek thinking 模式短响应：reasoning_content="" 是合法值（thinking 但
        // 本次思考为空），仍需回传字段满足多轮契约。ProviderMessageBuilder 把 ""
        // 也编码 marker → rewriter 抽 marker 拿到 "" → 注入到 reasoning_content。
        String content = ReasoningContentMarker.encode(null, "");
        String body = """
                {"messages":[
                  {"role":"assistant","content":%s,"tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]}
                ]}
                """.formatted(MAPPER.writeValueAsString(content));

        byte[] result = rewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        JsonNode assistant = MAPPER.readTree(result).get("messages").get(0);
        assertThat(assistant.has("reasoning_content")).isTrue();
        assertThat(assistant.get("reasoning_content").asText()).isEmpty();
        assertThat(assistant.has("content")).isFalse();  // marker 全部清理
    }

    @Test
    void user_和_tool_消息含相似文本不被误改() throws IOException {
        // user 内容偶然含控制字符（极低概率）也应 pass-through，filter 仅看 role=assistant
        String body = """
                {"messages":[
                  {"role":"user","content":"问题"},
                  {"role":"tool","tool_call_id":"c1","content":"工具输出"}
                ]}
                """;

        byte[] result = rewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNull();
    }

    @Test
    void 多轮_assistant_各自独立改写() throws IOException {
        String r1 = "第 1 轮思考";
        String r2 = "第 2 轮思考";
        String c1 = ReasoningContentMarker.encode(null, r1);
        String c2 = ReasoningContentMarker.encode(null, r2);
        String body = """
                {"messages":[
                  {"role":"user","content":"a"},
                  {"role":"assistant","content":%s,"tool_calls":[{"id":"x","type":"function","function":{"name":"f","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"x","content":"r"},
                  {"role":"assistant","content":%s,"tool_calls":[{"id":"y","type":"function","function":{"name":"g","arguments":"{}"}}]}
                ]}
                """.formatted(MAPPER.writeValueAsString(c1), MAPPER.writeValueAsString(c2));

        byte[] result = rewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        JsonNode root = MAPPER.readTree(result);
        assertThat(root.get("messages").get(1).get("reasoning_content").asText()).isEqualTo(r1);
        assertThat(root.get("messages").get(3).get("reasoning_content").asText()).isEqualTo(r2);
    }

    @Test
    void 合法_JSON_但非预期结构_pass_through() throws IOException {
        // 非预期 JSON 结构 → 返回 null pass-through；纯非 JSON 输入由父类
        // AbstractJsonBodyRewritingStrategy 的拦截骨架接住 JsonParseException 走原始 body
        assertThat(rewriter.rewriteBody("{}".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(rewriter.rewriteBody("[]".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(rewriter.rewriteBody(new byte[0])).isNull();
        // 含 messages 但 messages 为空数组
        assertThat(rewriter.rewriteBody("{\"messages\":[]}".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    void 清理模式_有_marker_仅剥离不注入字段() throws IOException {
        // 非 DeepSeek 的 OpenAI 兼容 provider（Qwen3 推理 / 智谱 / 月之暗面 / 硅基流动 等）
        // 走 OpenAi base 时 Spring AI 同样会写 metadata，触发 ProviderMessageBuilder 编码 marker；
        // 清理模式确保 marker 在请求出去前被剥离，不污染这些 provider 的 content 字段
        String content = ReasoningContentMarker.encode(null, "推理过程");
        String body = """
                {"messages":[
                  {"role":"assistant","content":%s,"tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]}
                ]}
                """.formatted(MAPPER.writeValueAsString(content));

        byte[] result = cleanupRewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        JsonNode assistant = MAPPER.readTree(result).get("messages").get(0);
        // 清理模式：不注入 reasoning_content 字段
        assertThat(assistant.has("reasoning_content")).isFalse();
        // marker 已被清理（content 全部由 marker 组成 → 字段被移除）
        assertThat(assistant.has("content")).isFalse();
        // tool_calls 保持原样
        assertThat(assistant.get("tool_calls").get(0).get("id").asText()).isEqualTo("c1");
    }

    @Test
    void 清理模式_无_marker_不做兜底空串注入() throws IOException {
        // 非 DeepSeek provider 不需要 reasoning_content 字段，无 marker 时完全 pass-through
        String body = """
                {"messages":[
                  {"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]}
                ]}
                """;

        byte[] result = cleanupRewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        // 清理模式不做兜底 → 无 marker + 有 tool_calls → pass-through
        assertThat(result).isNull();
    }

    @Test
    void content_为数组而非字符串_pass_through() throws IOException {
        // OpenAI 多模态消息 content 可能是数组（含 image_url 等），filter 不识别非字符串 content
        String body = """
                {"messages":[
                  {"role":"assistant","content":[{"type":"text","text":"a"}],"tool_calls":[]}
                ]}
                """;

        byte[] result = rewriter.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNull();
    }
}
