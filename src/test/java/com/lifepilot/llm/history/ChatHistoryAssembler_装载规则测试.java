package com.lifepilot.llm.history;

import com.lifepilot.llm.profile.MultiTurnHistoryRules;
import com.lifepilot.llm.thinking.DeepSeekThinkingProtocol;
import com.lifepilot.llm.thinking.NoopThinkingProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatHistoryAssembler 多轮装载规则测试。
 *
 * <p>覆盖核心场景：
 * <ol>
 *   <li>无差别注入规则（contentOnlyReasoning，DeepSeek/Qwen 等保守策略）下 assistant 消息回传 reasoning_content；</li>
 *   <li>OpenAI（标准）规则下 assistant 消息不附加 reasoning；</li>
 *   <li>无差别注入规则下历史 payload 缺 reasoning_content 时补空字符串占位；</li>
 *   <li>DeepSeek 规则（仅 tool_call 场景注入，<b>当前 profile 暂未启用</b>）下，无 tool_calls 时不回传 reasoning；</li>
 *   <li>DeepSeek 规则（同上）下，含非空 tool_calls 时回传 reasoning。</li>
 * </ol>
 *
 * <p>注：场景 4/5 守护 {@code deepseekContentOnlyReasoning()} 工厂的行为契约。
 * 当前 BuiltinProviderProfiles 的 DeepSeek/Ark profile 临时回退到 contentOnlyReasoning
 * 兜底（详见 {@code memory/project_reasoning_content_wiring_gap.md}）；待
 * reasoning_content 与 tool_calls 真实持久化 wiring 完成后切回 deepseekContentOnlyReasoning，
 * 届时这两个测试就是回归保护。
 *
 * @author zsg
 * @since 2026-04-27
 */
class ChatHistoryAssembler_装载规则测试 {

    @Test
    void DeepSeek_规则下_assistant_消息回传_reasoning_content() {
        var assembler = new ChatHistoryAssembler();
        var prevAssistant = Map.<String, Object>of(
                "role", "assistant",
                "content", "final",
                "reasoning_content", "上一轮思考"
        );
        var rules = MultiTurnHistoryRules.contentOnlyReasoning();
        var protocol = new DeepSeekThinkingProtocol();

        var msgs = assembler.assemble(
                List.of(prevAssistant, Map.of("role", "user", "content", "继续")),
                rules,
                protocol
        );

        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).role()).isEqualTo("assistant");
        assertThat(msgs.get(0).content()).isEqualTo("final");
        assertThat(msgs.get(0).reasoningContent()).isEqualTo("上一轮思考");
        assertThat(msgs.get(1).role()).isEqualTo("user");
        assertThat(msgs.get(1).content()).isEqualTo("继续");
    }

    @Test
    void OpenAI_规则下_assistant_消息不含_reasoning() {
        var assembler = new ChatHistoryAssembler();
        var prevAssistant = Map.<String, Object>of(
                "role", "assistant",
                "content", "final"
        );
        var rules = MultiTurnHistoryRules.standard();
        var protocol = new NoopThinkingProtocol();

        var msgs = assembler.assemble(
                List.of(prevAssistant, Map.of("role", "user", "content", "继续")),
                rules,
                protocol
        );

        assertThat(msgs.get(0).reasoningContent()).isNull();
    }

    @Test
    void DeepSeek_规则下_assistant_缺_reasoning_补空字符串() {
        var assembler = new ChatHistoryAssembler();
        var prevAssistant = Map.<String, Object>of(
                "role", "assistant",
                "content", "final"
        );

        var msgs = assembler.assemble(
                List.of(prevAssistant),
                MultiTurnHistoryRules.contentOnlyReasoning(),
                new DeepSeekThinkingProtocol()
        );

        assertThat(msgs.get(0).reasoningContent()).isEqualTo("");
    }

    @Test
    void DeepSeek_规则_无_tool_calls_时不回传_reasoning() {
        // DeepSeek 官方契约：仅在 assistant 含 tool_calls 时才需回传 reasoning_content；
        // 普通对话场景下 API 会忽略该字段，回传纯属浪费 prompt token。
        var assembler = new ChatHistoryAssembler();
        var prevAssistant = Map.<String, Object>of(
                "role", "assistant",
                "content", "上一轮答案",
                "reasoning_content", "上一轮的思考过程"
                // 注意：无 tool_calls 字段
        );
        var rules = MultiTurnHistoryRules.deepseekContentOnlyReasoning();
        var protocol = new DeepSeekThinkingProtocol();

        var msgs = assembler.assemble(List.of(prevAssistant), rules, protocol);

        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).role()).isEqualTo("assistant");
        assertThat(msgs.get(0).content()).isEqualTo("上一轮答案");
        // 关键断言：无 tool_calls 时 reasoning 不应被注入
        assertThat(msgs.get(0).reasoningContent()).isNull();
    }

    @Test
    void DeepSeek_规则_有_tool_calls_时回传_reasoning() {
        // DeepSeek 官方契约：含 tool_calls 时必须回传 reasoning_content，否则 V4 API 报 400
        var assembler = new ChatHistoryAssembler();
        var prevAssistant = Map.<String, Object>of(
                "role", "assistant",
                "content", "调用工具",
                "reasoning_content", "决定使用 search 工具",
                "tool_calls", List.of(Map.of(
                        "id", "call_1",
                        "type", "function",
                        "function", Map.of("name", "search", "arguments", "{\"q\":\"x\"}")
                ))
        );
        var rules = MultiTurnHistoryRules.deepseekContentOnlyReasoning();
        var protocol = new DeepSeekThinkingProtocol();

        var msgs = assembler.assemble(List.of(prevAssistant), rules, protocol);

        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).role()).isEqualTo("assistant");
        // 关键断言：含 tool_calls 时 reasoning_content 必须被注入（多轮契约硬要求）
        assertThat(msgs.get(0).reasoningContent()).isEqualTo("决定使用 search 工具");
    }
}
