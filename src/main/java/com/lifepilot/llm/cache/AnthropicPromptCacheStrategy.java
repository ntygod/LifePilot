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
 * 缓存写入一次性收取 125% 的标准单价, TTL 5 分钟。本策略注入两个缓存断点 (Anthropic 上限 4 个):</p>
 * <ol>
 *   <li><b>system 断点</b> — 缓存稳定的系统前缀 (角色/工具协议/skill+mcp catalog);</li>
 *   <li><b>对话尾部断点</b> — 缓存到 {@code messages} 最后一条为止的整段历史。</li>
 * </ol>
 *
 * <p>为什么需要尾部断点: 多轮 / 多迭代 ReAct 循环每次请求都完整重放历史。只标 system 时,
 * 系统前缀命中缓存, 但每一条历史 user/assistant/tool 块仍按全价重新编码。补一个尾部断点后,
 * Anthropic 对"从头到尾部断点"的整段前缀做增量缓存: 后续请求只为新增内容付全价, 历史前缀按 10% 计,
 * 多轮 Agent 循环上典型可省 5-10x 输入成本且响应更快, <b>不改变模型输出</b> (缓存对结果透明)。
 * 前缀不足最小缓存块 (多数模型 1024 token) 时该断点被 provider 忽略, 无副作用。</p>
 *
 * <p>body 改写规则:</p>
 * <ul>
 *   <li>{@code system} / {@code messages} 最后一条的 {@code content} 为字符串: 转成
 *       {@code [{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}]};</li>
 *   <li>为数组: 给最后一个 block 加 {@code cache_control: ephemeral}
 *       (若已存在则跳过, 避免覆盖调用方显式设置);</li>
 *   <li>两处都无法识别 / 不存在: pass-through 不改 (返回 {@code null})。</li>
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
     * 解析 JSON 请求体, 在 {@code system} 前缀与 {@code messages} 对话尾部各注入一个
     * {@code cache_control: ephemeral} 断点。任一处发生改写即返回新字节, 否则返回 {@code null} pass-through。
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

        boolean mutated = markSystemPrefix(rootObj);
        // 尾部断点独立判定: 即使没有 system field, 也可单独缓存对话历史
        mutated |= markConversationTail(rootObj);

        return mutated ? MAPPER.writeValueAsBytes(root) : null;
    }

    /**
     * 给根级 {@code system} field 注入缓存断点 (稳定系统前缀)。
     *
     * @return 是否发生改写
     */
    private boolean markSystemPrefix(ObjectNode rootObj) {
        JsonNode system = rootObj.get("system");
        if (system == null || system.isNull()) {
            return false;
        }
        if (system.isTextual()) {
            String text = system.asText();
            if (text.isEmpty()) {
                return false;
            }
            rootObj.set("system", toCachedTextBlocks(text));
            return true;
        }
        if (system.isArray() && !system.isEmpty()) {
            return markLastBlock((ArrayNode) system);
        }
        return false;
    }

    /**
     * 给 {@code messages} 最后一条消息的 content 末块注入缓存断点 (对话历史尾部)。
     *
     * <p>断点随对话增长逐轮后移, Anthropic 对"从头到该断点"的前缀做增量缓存:
     * 仅新增内容付全价, 历史前缀命中后按 10% 计费。content 为字符串时转成单 text block 数组;
     * 为 block 数组时给末块补 {@code cache_control} (已存在则跳过)。</p>
     *
     * @return 是否发生改写
     */
    private boolean markConversationTail(ObjectNode rootObj) {
        JsonNode messages = rootObj.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            return false;
        }
        JsonNode lastMsg = messages.get(messages.size() - 1);
        if (lastMsg == null || !lastMsg.isObject()) {
            return false;
        }
        ObjectNode lastMsgObj = (ObjectNode) lastMsg;
        JsonNode content = lastMsgObj.get("content");
        if (content == null || content.isNull()) {
            return false;
        }
        if (content.isTextual()) {
            String text = content.asText();
            if (text.isEmpty()) {
                return false;
            }
            lastMsgObj.set("content", toCachedTextBlocks(text));
            return true;
        }
        if (content.isArray() && !content.isEmpty()) {
            return markLastBlock((ArrayNode) content);
        }
        return false;
    }

    /** 把纯文本转成带 cache_control 的单 text block 数组。 */
    private ArrayNode toCachedTextBlocks(String text) {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        block.set("cache_control", ephemeral());
        arr.add(block);
        return arr;
    }

    /** 给 block 数组的最后一个对象 block 补 cache_control (已存在则跳过)；返回是否改写。 */
    private boolean markLastBlock(ArrayNode arr) {
        JsonNode last = arr.get(arr.size() - 1);
        if (last != null && last.isObject()) {
            ObjectNode lastBlock = (ObjectNode) last;
            if (!lastBlock.has("cache_control")) {
                lastBlock.set("cache_control", ephemeral());
                return true;
            }
        }
        return false;
    }

    /** 构造 {@code {"type":"ephemeral"}} 标记节点。 */
    private ObjectNode ephemeral() {
        ObjectNode ctrl = MAPPER.createObjectNode();
        ctrl.put("type", "ephemeral");
        return ctrl;
    }
}
