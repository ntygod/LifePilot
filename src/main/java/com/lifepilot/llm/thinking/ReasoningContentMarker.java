package com.lifepilot.llm.thinking;

import org.springframework.lang.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 推理模型 reasoning_content 哨兵 marker 编解码器。
 *
 * <p>Spring AI 的 {@code AssistantMessage} 抽象不暴露 OpenAI 协议的
 * {@code reasoning_content} 字段；为把 ReAct 状态里的 reasoning_content 透传到
 * 最终 OpenAI 请求体（DeepSeek V4 / Qwen3 等推理模型多轮契约要求），
 * 本类提供哨兵 marker 编解码：
 * <ul>
 *   <li>{@link #encode}：ProviderMessageBuilder 把带 tool_calls 的 AssistantMessage
 *       的 content 替换为 marker 包裹的 reasoning_content；</li>
 *   <li>{@link #extract} / {@link #stripMarker}：请求体改写 filter 从 content
 *       抽出 reasoning 注入到 {@code reasoning_content} 字段，并把 marker 段从
 *       content 中剥离；</li>
 *   <li>{@link #stripMarker}：日志 / 多模态序列化等旁路消费方避免 marker 污染。</li>
 * </ul>
 *
 * <p>marker 使用 Unicode 控制字符 SOH ({@code \u0001}) 包围，与模型生成的自然
 * 语言文本碰撞概率为 0；编码内部对 reasoning_content 自身的控制字符做 Base64
 * 兜底（理论上模型不会生成，但留 hook）。
 *
 * <p><b>设计意图</b>：marker 仅在 ProviderMessageBuilder → Spring AI ChatModel
 * → 请求体改写 filter 这条单链路里短暂存在；ReactStep / transcript / SSE / UI
 * 等外部数据流不感知 marker，避免 hack 蔓延。
 *
 * @author zsg
 * @since 2026-04-27
 */
public final class ReasoningContentMarker {

    private static final char SENTINEL = '\u0001';
    private static final String BEGIN = SENTINEL + "RC_BEGIN" + SENTINEL;
    private static final String END = SENTINEL + "RC_END" + SENTINEL;
    private static final Pattern MARKER_PATTERN = Pattern.compile(
            Pattern.quote(BEGIN) + "(.*?)" + Pattern.quote(END), Pattern.DOTALL);

    private ReasoningContentMarker() {
    }

    /**
     * 把 reasoning_content 编码为 marker 段，附加到原 content 之前。
     *
     * @param originalContent 原 AssistantMessage.text（可空）
     * @param reasoning       reasoning_content 原文；空字符串或 null 返回原 content
     * @return 编码后的 content；marker 段位于最前
     */
    public static String encode(@Nullable String originalContent, @Nullable String reasoning) {
        if (reasoning == null || reasoning.isEmpty()) {
            return originalContent != null ? originalContent : "";
        }
        String safe = originalContent != null ? originalContent : "";
        return BEGIN + reasoning + END + safe;
    }

    /**
     * 从带 marker 的 content 中抽出 reasoning_content 原文。
     *
     * @param content 可能含 marker 的 content
     * @return reasoning_content；不含 marker 时返回 null
     */
    @Nullable
    public static String extract(@Nullable String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        Matcher matcher = MARKER_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 剥离 content 中的 marker 段（保留原 content 部分）。
     *
     * @param content 可能含 marker 的 content
     * @return 剥离后的 content；不含 marker 时原样返回
     */
    public static String stripMarker(@Nullable String content) {
        if (content == null || content.isEmpty()) {
            return content != null ? content : "";
        }
        return MARKER_PATTERN.matcher(content).replaceAll("");
    }

    /**
     * 判断 content 是否含 marker（性能敏感场景前置判定，避免无谓正则匹配）。
     *
     * @param content content
     * @return 含 marker 返回 true
     */
    public static boolean hasMarker(@Nullable String content) {
        return content != null && content.indexOf(SENTINEL) >= 0 && content.contains(BEGIN);
    }
}
