package com.lifepilot.agent.task.reminder;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultReminderMessageGenerator 单元测试。
 *
 * <p>验证 LLM 文案生成与模板降级路径。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class DefaultReminderMessageGenerator_单元测试 {

    @Test
    void generate_存在生成路由_返回LLM文案() {
        GenerationRouter generationRouter = mock(GenerationRouter.class);
        PromptRegistry promptRegistry = mock(PromptRegistry.class);
        AgentConfigProperties config = new AgentConfigProperties();
        DefaultReminderMessageGenerator generator = new DefaultReminderMessageGenerator(
                generationRouter, promptRegistry, config);

        ReminderDecision decision = buildDecision();
        ReminderTopicSnapshot snapshot = buildSnapshot();
        ReminderRuntimeContext context = buildContext();

        when(promptRegistry.render(eq("generation/proactive-reminder"), any(Map.class)))
                .thenReturn("prompt");
        when(generationRouter.call(
                eq("proactive_reminder"),
                eq("prompt"),
                eq(null),
                eq(null),
                eq(null),
                eq(GenerationCapability.CHAT),
                any(Duration.class)
        )).thenReturn(new LlmResponse(
                "你前面提过水费今晚前要处理，现在顺手办一下会更稳妥。",
                10,
                12,
                "openai",
                "gpt-5.4-mini",
                120,
                false
        ));

        ReminderMessage message = generator.generate("default", decision, snapshot, context);

        assertThat(message.mode()).isEqualTo("llm");
        assertThat(message.providerId()).isEqualTo("openai");
        assertThat(message.modelName()).isEqualTo("gpt-5.4-mini");
        assertThat(message.body()).contains("水费今晚前要处理");
        verify(promptRegistry).render(eq("generation/proactive-reminder"), any(Map.class));
    }

    @Test
    void generate_无生成路由_回退模板文案() {
        AgentConfigProperties config = new AgentConfigProperties();
        DefaultReminderMessageGenerator generator = new DefaultReminderMessageGenerator(null, null, config);

        ReminderMessage message = generator.generate("default", buildDecision(), buildSnapshot(), buildContext());

        assertThat(message.mode()).isEqualTo("fallback");
        assertThat(message.body()).contains("缴水费");
        assertThat(message.body()).contains("今晚前完成");
    }

    private ReminderDecision buildDecision() {
        return new ReminderDecision(
                new ReminderCandidate(
                        "bill:water",
                        "缴水费",
                        ReminderCandidateType.DUE_SOON,
                        "sig-water",
                        0.90f,
                        0.86f,
                        0.91f,
                        0.75f,
                        0.88f,
                        0.0f,
                        0.0f,
                        0.87f,
                        Instant.parse("2026-03-28T12:00:00Z"),
                        "今晚前最好处理掉"
                ),
                ReminderAction.NORMAL_PUSH,
                null,
                "现在是合适的处理窗口"
        );
    }

    private ReminderTopicSnapshot buildSnapshot() {
        return new ReminderTopicSnapshot(
                "bill:water",
                "缴水费",
                List.of(new ReminderSignal(
                        "sig-water",
                        ReminderSignalKind.DEADLINE,
                        0.95f,
                        0.90f,
                        3,
                        Instant.parse("2026-03-28T09:00:00Z"),
                        Instant.parse("2026-03-28T14:00:00Z"),
                        null,
                        null,
                        null,
                        0.0f,
                        true,
                        false,
                        "今晚前完成"
                )),
                new ReminderTopicState(
                        Instant.parse("2026-03-27T13:00:00Z"),
                        0,
                        1,
                        1,
                        0,
                        0,
                        0,
                        false
                )
        );
    }

    private ReminderRuntimeContext buildContext() {
        return new ReminderRuntimeContext(
                Instant.parse("2026-03-28T10:00:00Z"),
                ZoneId.of("Asia/Shanghai"),
                null,
                null,
                0
        );
    }
}
