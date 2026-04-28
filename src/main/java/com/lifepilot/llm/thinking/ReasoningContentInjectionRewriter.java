package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lifepilot.llm.cache.AbstractJsonBodyRewritingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.io.IOException;

/**
 * 推理模型 reasoning_content 请求体改写器（DeepSeek V4 / Qwen3 等）。
 *
 * <p>DeepSeek 官方多轮契约：在两个 user 消息之间，若 assistant 进行了工具调用，
 * 则中间 assistant 消息的 {@code reasoning_content} 必须回传给 API；缺字段或字段缺失
 * 都会导致 {@code 400 - The reasoning_content in the thinking mode must be passed back}。
 * Spring AI 的 {@code AssistantMessage} 抽象不直接暴露 {@code reasoning_content} 字段，
 * 故 {@link com.lifepilot.agent.context.ProviderMessageBuilder} 把 ReactStep 上的
 * reasoning_content 编码进 AssistantMessage.content 的 sentinel marker 段，
 * 由本改写器在 OpenAI 请求体出去前抽出 marker 内容、清理 marker、并按需注入到协议字段。
 *
 * <p>双模式设计（避免污染非 DeepSeek 的 OpenAI 兼容 provider）：
 * <ul>
 *   <li>{@link #FULL_INJECTION}（完整模式，DeepSeek 协议 provider 挂载）：
 *       抽 marker → 设置 {@code reasoning_content} 字段 → 清理 marker。</li>
 *   <li>{@link #CLEANUP_ONLY}（清理模式，其他 OpenAI 兼容 provider 挂载）：
 *       仅清理 marker（防 SOH 控制字符污染请求体），不注入 reasoning_content 字段。
 *       Qwen3 推理 / 智谱推理 / 月之暗面 / 硅基流动等也走 OpenAi base，
 *       Spring AI 同样会写 metadata["reasoningContent"]，进而触发 marker 编码；
 *       本模式确保 marker 在请求出去前被剥离，不污染这些 provider 的请求体。</li>
 * </ul>
 *
 * <p><b>不做兜底空串注入</b>：响应解析链路（{@link com.lifepilot.llm.adapter.AbstractProviderAdapter#chunkToEvents}
 * + {@code StreamingCallback} / {@code NonStreamingCallback} 同步 metadata 读取）
 * 已彻底覆盖流式 + 同步两条路径，DeepSeek 调用必然有 marker。若未来新路径接入忘了
 * 透传 reasoning，宁可让 DeepSeek 以 400 立即暴露问题，也不静默退化为"上一轮没思考"。</p>
 *
 * <p><b>仅处理文本型 content</b>：OpenAI 多模态请求 content 为数组（含 image_url 等），
 * 当前 DeepSeek/Qwen3 等推理模型均非多模态，不进多模态路径；该分支保持 pass-through。</p>
 *
 * <p>改写规则（marker 真值链路是唯一通道，没有兜底）：
 * <ul>
 *   <li>遍历 {@code messages[]}，对 {@code role=assistant} 消息做检查；</li>
 *   <li>若 {@code content} 含 {@link ReasoningContentMarker} 标记：
 *       <ul>
 *         <li>抽出 marker 内 reasoning_content 文本（可能为空字符串）；</li>
 *         <li>完整模式下设置 {@code reasoning_content} 字段，清理模式跳过；</li>
 *         <li>从 content 移除 marker 段（marker 之外的 content 保留）；</li>
 *         <li>若移除后 content 为空，删除 content 字段（避免 OpenAI API 校验告警）。</li>
 *       </ul></li>
 *   <li>不含 marker 的 assistant 消息：保持原样不改动。
 *       <p>marker 由 {@link com.lifepilot.agent.context.ProviderMessageBuilder} 在
 *       构造 AssistantMessage with tool_calls 时基于 ReactStep.ToolCall.reasoningContent
 *       编码（{@code != null} 都编码，包括 ""）；
 *       {@link com.lifepilot.agent.callback.NonStreamingCallback} /
 *       {@link com.lifepilot.agent.callback.StreamingCallback} 从 Spring AI metadata
 *       提取 reasoning 时不跳过空串。整条链路无空判定，DeepSeek thinking 模式无论
 *       reasoning 是否为空都走 marker 路径。</li>
 * </ul>
 *
 * <p>两端拦截骨架（RestClient / WebClient）由 {@link AbstractJsonBodyRewritingStrategy}
 * 统一提供，本类只声明 JSON 改写细节。本类不参与 prompt cache 路由
 * （{@link com.lifepilot.llm.profile.PromptCacheStrategyId}），由
 * {@code ProviderAdapterFactory} 在 DEEPSEEK profile 直接挂载。
 *
 * @author zsg
 * @since 2026-04-27
 */
public final class ReasoningContentInjectionRewriter extends AbstractJsonBodyRewritingStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReasoningContentInjectionRewriter.class);

    /**
     * 完整模式 — DeepSeek 协议 provider 挂载（DEEPSEEK_OFFICIAL / VOLCENGINE_ARK）。
     *
     * <p>抽 marker 内 reasoning 注入 {@code reasoning_content} 字段；带 tool_calls
     * 但缺 reasoning_content 的 assistant 兜底注入空字符串避免 DeepSeek 400。
     */
    public static final ReasoningContentInjectionRewriter FULL_INJECTION =
            new ReasoningContentInjectionRewriter(true);

    /**
     * 清理模式 — 其他 OpenAI 兼容 provider 挂载（Qwen / OpenAI / 智谱 / 月之暗面 / 硅基流动 / Ollama 等）。
     *
     * <p>仅清理 marker 防控制字符污染请求体，不注入 reasoning_content 字段。
     * Qwen3 / 其他厂商推理模型走 OpenAi base 时 Spring AI 同样会写 metadata，
     * 进而触发 ProviderMessageBuilder 编码 marker；本模式确保不影响这些 provider。
     */
    public static final ReasoningContentInjectionRewriter CLEANUP_ONLY =
            new ReasoningContentInjectionRewriter(false);

    private final boolean injectField;

    private ReasoningContentInjectionRewriter(boolean injectField) {
        this.injectField = injectField;
    }

    /** 暴露模式给单测断言。 */
    boolean injectFieldForTesting() {
        return injectField;
    }

    @Override
    public String name() {
        return injectField ? "reasoning-content-injection" : "reasoning-content-cleanup";
    }

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
            if (role == null || !"assistant".equals(role.asText())) continue;
            ObjectNode msgObj = (ObjectNode) msg;

            JsonNode content = msg.get("content");
            String text = (content != null && content.isTextual()) ? content.asText() : null;
            boolean hasMarker = text != null && ReasoningContentMarker.hasMarker(text);

            if (!hasMarker) continue;
            // 抽 marker 内 reasoning，永远清理 marker（防控制字符污染请求体）
            String reasoning = ReasoningContentMarker.extract(text);
            String stripped = ReasoningContentMarker.stripMarker(text);
            if (injectField && reasoning != null) {
                // 完整模式：DeepSeek 协议 provider 注入 reasoning_content 字段（含空字符串）
                msgObj.put("reasoning_content", reasoning);
            }
            if (stripped == null || stripped.isEmpty()) {
                msgObj.remove("content");
            } else {
                msgObj.put("content", stripped);
            }
            mutated = true;
        }
        if (mutated && log.isDebugEnabled()) {
            log.debug("reasoning rewriter body 已改写: mode={}, bytesBefore={}",
                    injectField ? "FULL_INJECTION" : "CLEANUP_ONLY", body.length);
        }
        return mutated ? MAPPER.writeValueAsBytes(root) : null;
    }

    /** 内部测试钩子：用包外 ObjectMapper 解析改写结果（调试场景）。 */
    @SuppressWarnings("unused")
    static ObjectMapper testingMapper() {
        return MAPPER;
    }
}
