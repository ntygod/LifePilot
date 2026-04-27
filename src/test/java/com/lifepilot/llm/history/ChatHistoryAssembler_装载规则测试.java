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
 * <p>覆盖三个核心场景：
 * <ol>
 *   <li>DeepSeek 规则下 assistant 消息回传 reasoning_content；</li>
 *   <li>OpenAI（标准）规则下 assistant 消息不附加 reasoning；</li>
 *   <li>DeepSeek 规则下历史 payload 缺 reasoning_content 时补 dummy 占位。</li>
 * </ol>
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
}
