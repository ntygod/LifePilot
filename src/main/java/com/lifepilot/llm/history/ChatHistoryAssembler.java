package com.lifepilot.llm.history;

import com.lifepilot.llm.profile.MultiTurnHistoryRules;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多轮 history 装载器 — 按 {@code ProviderProfile.historyRules} 与 {@link ThinkingProtocol}
 * 把 {@code session_transcript_entries.payload_json} 反序列化的 Map 序列化为
 * 适配器消费的 {@link ProviderMessage} 列表。
 *
 * <p>分派规则：按 payload 的 role 字段分派（system / user / assistant / 其他），
 * assistant 消息走 {@link AssistantMessageBuilder} 构造，并在
 * {@link MultiTurnHistoryRules#injectReasoning()} 为 {@code true} 时由 protocol
 * 注入历史 reasoning。
 *
 * <p><b>当前未接入主链路</b>：ReactAgentLoop 走 {@code ProviderMessageBuilder} +
 * Spring AI Message 路径装载多轮，本类暂为孤儿组件。DeepSeek 多轮
 * reasoning_content 注入实际由请求体改写 filter
 * （{@code com.lifepilot.llm.thinking.ReasoningContentInjectionRewriter}）
 * 在 OpenAI 请求出去前完成。本类保留待未来跨 turn 历史装载 wiring 启用。
 *
 * @author zsg
 * @since 2026-04-27
 */
@Component
public class ChatHistoryAssembler {

    /**
     * 装载历史消息。
     *
     * @param payloads 按时间序的 payload_json 反序列化结果列表（每项为 {@code Map<String, Object>}）
     * @param rules    Provider 多轮规则
     * @param protocol Provider thinking 协议
     * @return 适配器可消费的 {@link ProviderMessage} 不可变列表
     */
    public List<ProviderMessage> assemble(List<Map<String, Object>> payloads,
                                          MultiTurnHistoryRules rules,
                                          ThinkingProtocol protocol) {
        var result = new ArrayList<ProviderMessage>(payloads.size());
        for (var payload : payloads) {
            String role = String.valueOf(payload.getOrDefault("role", "user"));
            String content = String.valueOf(payload.getOrDefault("content", ""));
            switch (role) {
                case "system" -> result.add(ProviderMessage.system(content));
                case "user" -> result.add(ProviderMessage.user(content));
                case "assistant" -> result.add(buildAssistant(payload, rules, protocol));
                default -> result.add(new ProviderMessage(
                        role, content, null, null, List.of(), Map.of()));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 构造 assistant 消息：按规则注入 reasoning。
     *
     * <p>当 {@link MultiTurnHistoryRules#injectReasoningOnlyWithToolCalls()} 为 {@code true}
     * 时，仅在 assistant payload 含非空 tool_calls 列表时才注入 reasoning_content
     * （DeepSeek 官方契约：无 tool_call 场景 API 忽略该字段，回传纯属浪费 prompt token）。
     *
     * <p>tool_calls 注入留给 Phase 7 扩展；当前先把 content + reasoning 链路打通。
     */
    private ProviderMessage buildAssistant(Map<String, Object> payload,
                                           MultiTurnHistoryRules rules,
                                           ThinkingProtocol protocol) {
        var builder = new AssistantMessageBuilder()
                .content(String.valueOf(payload.getOrDefault("content", "")));
        if (rules.injectReasoning()) {
            boolean shouldInject = true;
            if (rules.injectReasoningOnlyWithToolCalls()) {
                Object toolCalls = payload.get("tool_calls");
                shouldInject = (toolCalls instanceof List<?> list && !list.isEmpty());
            }
            if (shouldInject) {
                protocol.injectHistoryReasoning(builder, payload);
            }
        }
        // tool_calls 注入由 Phase 7 实施时按 MultiTurnHistoryRules.injectToolCalls 扩展
        return builder.build();
    }
}
