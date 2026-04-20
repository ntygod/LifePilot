package com.lifepilot.llm.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.lang.Nullable;

import java.io.IOException;

/**
 * DashScope (百炼) 显式 prompt 缓存策略。
 *
 * <p>DashScope 对 qwen 系列支持显式 prompt caching: 需要把 OpenAI 兼容协议里
 * {@code messages[role=system].content} 从纯字符串改成结构化内容数组
 * {@code [{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}]},
 * 百炼看到 {@code cache_control} 标记就对该块强制缓存, 命中按标准单价 10% 计费, TTL 5 分钟。
 * Spring AI 的 {@code SystemMessage(String)} 默认发纯字符串, 不触发显式缓存,
 * 本策略在 HTTP 边界改写 request body。</p>
 *
 * <p>两端拦截骨架 (RestClient / WebClient) 由 {@link AbstractJsonBodyRewritingStrategy}
 * 统一提供, 本类只声明 JSON 改写细节。</p>
 *
 * <p>DashScope 限制单请求最多 4 个 cache_control marker, 且最小缓存块 1024 tokens —
 * 短 prompt 标了也不会触发, 不影响正确性。通常只有一条 system message, 标完即退。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public final class DashScopePromptCacheStrategy extends AbstractJsonBodyRewritingStrategy {

    /** 单例 — 策略无状态, 与 {@link NoopPromptCacheStrategy#INSTANCE} / {@link AnthropicPromptCacheStrategy#INSTANCE} 风格一致。 */
    public static final DashScopePromptCacheStrategy INSTANCE = new DashScopePromptCacheStrategy();

    private DashScopePromptCacheStrategy() {
    }

    @Override
    public String name() {
        return "dashscope";
    }

    /**
     * 解析 JSON 请求体, 把第一条 role=system message 的 content 从 String 改写为结构化数组
     * 含 cache_control ephemeral 标记。返回 {@code null} 表示无需改动 (非预期结构 / 已是数组 / 无 system)。
     */
    @Override
    @Nullable
    protected byte[] rewriteBody(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return null;
        }
        JsonNode root = MAPPER.readTree(body);
        if (!root.isObject() || !root.has("messages")) {
            return null;
        }
        JsonNode messages = root.get("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            return null;
        }
        boolean mutated = false;
        for (JsonNode msg : (ArrayNode) messages) {
            if (!msg.isObject()) continue;
            JsonNode role = msg.get("role");
            if (role == null || !"system".equals(role.asText())) continue;
            JsonNode content = msg.get("content");
            if (content == null || !content.isTextual()) continue;
            String text = content.asText();
            if (text.isEmpty()) continue;

            ObjectNode msgObj = (ObjectNode) msg;
            ArrayNode contentArr = MAPPER.createArrayNode();
            ObjectNode block = MAPPER.createObjectNode();
            block.put("type", "text");
            block.put("text", text);
            ObjectNode ctrl = MAPPER.createObjectNode();
            ctrl.put("type", "ephemeral");
            block.set("cache_control", ctrl);
            contentArr.add(block);
            msgObj.set("content", contentArr);
            mutated = true;
            // 通常只有一条 system message, 标完即退; 多条理论可以各自打, 但 DashScope 限制
            // 单请求最多 4 个 cache_control marker, 保守只标第一条
            break;
        }
        return mutated ? MAPPER.writeValueAsBytes(root) : null;
    }
}
