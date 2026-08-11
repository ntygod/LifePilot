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
 * <p>改写规则（基于 DeepSeek 实测协议事实）：
 * <ul>
 *   <li>遍历 {@code messages[]}，对所有 {@code role=assistant} 消息做处理；</li>
 *   <li>若 {@code content} 含 {@link ReasoningContentMarker} 标记：
 *       <ul>
 *         <li>抽出 marker 内 reasoning_content 文本（可能为空字符串）；</li>
 *         <li>完整模式下设置 {@code reasoning_content} 字段，清理模式跳过；</li>
 *         <li>从 content 移除 marker 段（marker 之外的 content 保留）；</li>
 *         <li>若移除后 content 为空，删除 content 字段。</li>
 *       </ul></li>
 *   <li>完整模式下，无论是否含 marker、是否含 tool_calls，<b>所有 assistant 必须含
 *       {@code reasoning_content} 字段</b>。这是 DeepSeek 协议实测事实：
 *       <ul>
 *         <li>实测 1：DeepSeek thinking 模式校验所有 assistant message 都需 reasoning_content
 *             字段（不只是 with tool_calls 的），缺字段直接 400 — 跟官方文档"无 tool_call
 *             场景 reasoning_content 字段被忽略"的描述不符；以实测为准；</li>
 *         <li>实测 2：Spring AI 1.1.3 同步 RestClient 路径不写 reasoning 到 metadata
 *             （metadataKeys=[role, messageType, refusal, finishReason, annotations,
 *             index, id]，无 reasoningContent / reasoning_content 任何 key），
 *             marker 真值链路在同步路径下永远拿不到真值，所有 reasoning 都是 ""；</li>
 *         <li>实测 3：ProviderMessageBuilder 对 ReactStep.Answer / Suspend 等产出
 *             AssistantMessage 的分支不编码 marker（仅 ToolCall 合并产物编码），
 *             这些 assistant 在多轮 messages 里缺字段。</li>
 *       </ul>
 *       综合结论：完整模式下 assistant 缺 {@code reasoning_content} 字段时强制注入空字符串
 *       占位（如有 marker 则抽出真值，否则空串）。这<b>不是兜底</b>，是 DeepSeek 协议
 *       合规性的核心要求。</li>
 *   <li>清理模式（其他 OpenAI 兼容 provider）：仅清理 marker，不注入字段。</li>
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
        // 诊断统计：记录 messages 数组里 assistant 消息的关键属性，便于排查"rewriter 命中
        // 但 DeepSeek 仍 400"这类问题（marker 没编码 / 字段没注入 / 字段值不对等）
        int assistantTotal = 0;
        int assistantWithToolCalls = 0;
        int assistantWithMarker = 0;
        int assistantWithReasoningField = 0;

        boolean mutated = false;
        for (JsonNode msg : (ArrayNode) messages) {
            if (!msg.isObject()) continue;
            JsonNode role = msg.get("role");
            if (role == null || !"assistant".equals(role.asText())) continue;
            assistantTotal++;
            ObjectNode msgObj = (ObjectNode) msg;

            JsonNode toolCallsNode = msg.get("tool_calls");
            if (toolCallsNode != null && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
                assistantWithToolCalls++;
            }
            JsonNode content = msg.get("content");
            String text = (content != null && content.isTextual()) ? content.asText() : null;
            boolean hasMarker = text != null && ReasoningContentMarker.hasMarker(text);
            if (hasMarker) assistantWithMarker++;
            if (msg.has("reasoning_content")) assistantWithReasoningField++;

            if (hasMarker) {
                // 抽 marker 内 reasoning，永远清理 marker（防控制字符污染请求体）
                String reasoning = ReasoningContentMarker.extract(text);
                String stripped = ReasoningContentMarker.stripMarker(text);
                if (injectField && reasoning != null) {
                    msgObj.put("reasoning_content", reasoning);
                    assistantWithReasoningField++;
                }
                if (stripped == null || stripped.isEmpty()) {
                    msgObj.remove("content");
                } else {
                    msgObj.put("content", stripped);
                }
                mutated = true;
                continue;
            }

            // 完整模式：所有缺字段的 assistant 注入空字符串占位（DeepSeek 实测协议要求）
            // 不再区分有无 tool_calls —— 实测所有 assistant 都必须含 reasoning_content
            // 字段，缺字段 400。这是 ProviderMessageBuilder 对 Answer / Suspend 等
            // 非 ToolCall step 产出的 AssistantMessage 没编码 marker 的最终防线。
            if (injectField && !msg.has("reasoning_content")) {
                msgObj.put("reasoning_content", "");
                assistantWithReasoningField++;
                mutated = true;
            }
        }
        log.debug("reasoning rewriter 命中: mode={}, totalMessages={}, assistantTotal={}, "
                        + "assistantWithToolCalls={}, assistantWithMarker={}, "
                        + "assistantWithReasoningField={}, mutated={}",
                injectField ? "FULL_INJECTION" : "CLEANUP_ONLY",
                messages.size(), assistantTotal,
                assistantWithToolCalls, assistantWithMarker,
                assistantWithReasoningField, mutated);

        // 关键诊断：dump 改写后所有 assistant 消息的结构，定位"rewriter 命中但 DeepSeek
        // 仍 400"问题（哪条 assistant 缺 reasoning_content / 字段值不对等）
        if (injectField && log.isDebugEnabled()) {
            int idx = 0;
            for (JsonNode msg : (ArrayNode) messages) {
                if (!msg.isObject()) { idx++; continue; }
                String role = msg.path("role").asText("?");
                if (!"assistant".equals(role)) { idx++; continue; }
                JsonNode tc = msg.get("tool_calls");
                int toolCallsCount = (tc != null && tc.isArray()) ? tc.size() : 0;
                JsonNode contentNode = msg.get("content");
                int contentLen = (contentNode != null && contentNode.isTextual())
                        ? contentNode.asText().length() : -1;
                JsonNode reasoningNode = msg.get("reasoning_content");
                String reasoningPreview = reasoningNode == null
                        ? "<MISSING>"
                        : (reasoningNode.isTextual()
                                ? "len=" + reasoningNode.asText().length()
                                  + (reasoningNode.asText().isEmpty() ? " (EMPTY)" : "")
                                : "<NOT_STRING:" + reasoningNode.getNodeType() + ">");
                log.debug("  assistant[{}]: toolCalls={}, contentLen={}, reasoning_content={}",
                        idx, toolCallsCount, contentLen, reasoningPreview);
                idx++;
            }
        }
        return mutated ? MAPPER.writeValueAsBytes(root) : null;
    }

    /** 内部测试钩子：用包外 ObjectMapper 解析改写结果（调试场景）。 */
    @SuppressWarnings("unused")
    static ObjectMapper testingMapper() {
        return MAPPER;
    }
}
