package com.lifepilot.llm.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnthropicPromptCacheStrategy} 请求体改写契约测试。
 *
 * <p>覆盖 system 前缀断点 + 对话尾部断点双断点注入，验证：
 * <ul>
 *   <li>字符串 system → 转 text block 数组并带 cache_control；</li>
 *   <li>messages 最后一条字符串 content → 转 text block 数组并带 cache_control；</li>
 *   <li>messages 最后一条 block 数组 content → 末块补 cache_control；</li>
 *   <li>仅 system 无 messages / 仅 messages 无 system → 各自独立生效；</li>
 *   <li>已存在 cache_control 的末块 → 不覆盖；</li>
 *   <li>非预期结构 → 返回 null pass-through。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-06-16
 */
@DisplayName("AnthropicPromptCacheStrategy 缓存断点注入")
class AnthropicPromptCacheStrategy_缓存断点测试 {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AnthropicPromptCacheStrategy strategy = AnthropicPromptCacheStrategy.INSTANCE;

    @Test
    @DisplayName("字符串 system + 字符串尾部消息 → 双断点均注入")
    void 字符串system与字符串尾部消息双断点注入() throws IOException {
        String body = """
                {
                  "system": "你是知微，一个可靠的助手。",
                  "messages": [
                    {"role": "user", "content": "第一轮问题"},
                    {"role": "assistant", "content": "第一轮回答"},
                    {"role": "user", "content": "第二轮问题"}
                  ]
                }
                """;

        byte[] result = strategy.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        JsonNode root = MAPPER.readTree(result);

        // system 转为 text block 数组并带 cache_control
        JsonNode system = root.get("system");
        assertThat(system.isArray()).isTrue();
        JsonNode systemBlock = system.get(system.size() - 1);
        assertThat(systemBlock.path("type").asText()).isEqualTo("text");
        assertThat(systemBlock.path("text").asText()).isEqualTo("你是知微，一个可靠的助手。");
        assertThat(systemBlock.path("cache_control").path("type").asText()).isEqualTo("ephemeral");

        // 最后一条 user 消息 content 转为 text block 数组并带 cache_control
        JsonNode messages = root.get("messages");
        JsonNode lastContent = messages.get(messages.size() - 1).get("content");
        assertThat(lastContent.isArray()).isTrue();
        JsonNode lastBlock = lastContent.get(lastContent.size() - 1);
        assertThat(lastBlock.path("text").asText()).isEqualTo("第二轮问题");
        assertThat(lastBlock.path("cache_control").path("type").asText()).isEqualTo("ephemeral");

        // 中间历史消息不应被改写
        assertThat(messages.get(0).get("content").isTextual()).isTrue();
    }

    @Test
    @DisplayName("尾部消息为 block 数组 → 末块补 cache_control")
    void 尾部消息为block数组时末块补标记() throws IOException {
        String body = """
                {
                  "system": "系统提示",
                  "messages": [
                    {"role": "user", "content": [
                      {"type": "text", "text": "看图"},
                      {"type": "text", "text": "再补充一句"}
                    ]}
                  ]
                }
                """;

        byte[] result = strategy.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        JsonNode messages = MAPPER.readTree(result).get("messages");
        JsonNode content = messages.get(0).get("content");
        // 末块补 cache_control，前块不动
        assertThat(content.get(0).has("cache_control")).isFalse();
        assertThat(content.get(1).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    @DisplayName("仅 messages 无 system → 仅尾部断点生效")
    void 仅messages无system时仅尾部断点生效() throws IOException {
        String body = """
                {
                  "messages": [
                    {"role": "user", "content": "你好"}
                  ]
                }
                """;

        byte[] result = strategy.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        JsonNode root = MAPPER.readTree(result);
        assertThat(root.has("system")).isFalse();
        JsonNode lastContent = root.get("messages").get(0).get("content");
        assertThat(lastContent.isArray()).isTrue();
        assertThat(lastContent.get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    @DisplayName("末块已有 cache_control → 不覆盖且不重复改写")
    void 末块已有标记时不覆盖() throws IOException {
        String body = """
                {
                  "messages": [
                    {"role": "user", "content": [
                      {"type": "text", "text": "已标记", "cache_control": {"type": "ephemeral"}}
                    ]}
                  ]
                }
                """;

        // system 缺失 + 尾部已标记 → 无任何可改写点 → pass-through
        byte[] result = strategy.rewriteBody(body.getBytes(StandardCharsets.UTF_8));
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("非预期结构 → 返回 null pass-through")
    void 非预期结构返回null() throws IOException {
        assertThat(strategy.rewriteBody("{}".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(strategy.rewriteBody("[]".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(strategy.rewriteBody(new byte[0])).isNull();
        // system 为空字符串 + messages 空数组 → 无改写点
        assertThat(strategy.rewriteBody(
                "{\"system\":\"\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    @DisplayName("空字符串尾部 content → 不注入尾部断点")
    void 空字符串尾部content不注入() throws IOException {
        String body = """
                {
                  "system": "系统提示",
                  "messages": [
                    {"role": "assistant", "content": ""}
                  ]
                }
                """;

        byte[] result = strategy.rewriteBody(body.getBytes(StandardCharsets.UTF_8));

        // system 仍被标记，尾部空 content 跳过
        assertThat(result).isNotNull();
        JsonNode root = MAPPER.readTree(result);
        assertThat(root.get("system").isArray()).isTrue();
        assertThat(root.get("messages").get(0).get("content").isTextual()).isTrue();
        assertThat(root.get("messages").get(0).get("content").asText()).isEmpty();
    }
}
