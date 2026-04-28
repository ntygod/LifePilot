package com.lifepilot.llm.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.lang.Nullable;

import java.io.IOException;

/**
 * Anthropic Claude 显式 prompt 缓存策略。
 *
 * <p>Anthropic Messages API 支持在 {@code system} / {@code tools} / {@code messages[].content[]}
 * 任意 block 上打 {@code cache_control: {type: ephemeral}}, 命中按原单价 10% 计费,
 * 缓存写入一次性收取 125% 的标准单价, TTL 5 分钟。本策略仅对最高影响面 —
 * 请求根的 {@code system} field 注入标记, 覆盖绝大多数长 system prompt 场景。</p>
 *
 * <p>body 改写规则:</p>
 * <ul>
 *   <li>{@code system} 为字符串: 转成
 *       {@code [{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}]};</li>
 *   <li>{@code system} 为数组: 给最后一个 block 加 {@code cache_control: ephemeral}
 *       (若已存在则跳过, 避免覆盖调用方显式设置);</li>
 *   <li>没有 {@code system} field 或结构不识别: pass-through 不改。</li>
 * </ul>
 *
 * <p>两端拦截骨架 (RestClient / WebClient) 由 {@link AbstractJsonBodyRewritingStrategy}
 * 统一提供, 本类只声明 JSON 改写细节。</p>
 *
 * <p>响应命中信息 ({@code usage.cache_read_input_tokens} /
 * {@code usage.cache_creation_input_tokens}) 由 Adapter 在 chunkToEvents / 非流式响应映射阶段
 * 解析后通过 {@link com.lifepilot.llm.stream.UsageEvent#cachedInputTokens()} 字段直传, 无需本策略处理。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public final class AnthropicPromptCacheStrategy extends AbstractJsonBodyRewritingStrategy {

    /** 单例 — 策略无状态, 与 {@link NoopPromptCacheStrategy#INSTANCE} 风格一致。 */
    public static final AnthropicPromptCacheStrategy INSTANCE = new AnthropicPromptCacheStrategy();

    private AnthropicPromptCacheStrategy() {
    }

    @Override
    public String name() {
        return "anthropic";
    }

    /**
     * 解析 JSON 请求体, 对根级 {@code system} field 注入 {@code cache_control: ephemeral}。
     */
    @Override
    @Nullable
    protected byte[] rewriteBody(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return null;
        }
        JsonNode root = MAPPER.readTree(body);
        if (!root.isObject()) {
            return null;
        }
        ObjectNode rootObj = (ObjectNode) root;
        JsonNode system = rootObj.get("system");
        if (system == null || system.isNull()) {
            return null;
        }

        boolean mutated = false;

        if (system.isTextual()) {
            // 字符串形态: 转成 array-of-one + cache_control
            String text = system.asText();
            if (text.isEmpty()) {
                return null;
            }
            ArrayNode arr = MAPPER.createArrayNode();
            ObjectNode block = MAPPER.createObjectNode();
            block.put("type", "text");
            block.put("text", text);
            ObjectNode ctrl = MAPPER.createObjectNode();
            ctrl.put("type", "ephemeral");
            block.set("cache_control", ctrl);
            arr.add(block);
            rootObj.set("system", arr);
            mutated = true;
        } else if (system.isArray() && !system.isEmpty()) {
            // 数组形态: 给最后一个 block 加 cache_control (若未设置)
            ArrayNode arr = (ArrayNode) system;
            JsonNode last = arr.get(arr.size() - 1);
            if (last != null && last.isObject()) {
                ObjectNode lastBlock = (ObjectNode) last;
                if (!lastBlock.has("cache_control")) {
                    ObjectNode ctrl = MAPPER.createObjectNode();
                    ctrl.put("type", "ephemeral");
                    lastBlock.set("cache_control", ctrl);
                    mutated = true;
                }
            }
        }
        return mutated ? MAPPER.writeValueAsBytes(root) : null;
    }
}
