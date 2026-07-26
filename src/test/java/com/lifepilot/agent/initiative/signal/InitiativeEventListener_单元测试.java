package com.lifepilot.agent.initiative.signal;

import com.lifepilot.agent.initiative.InitiativeEngine;
import com.lifepilot.conversation.event.ConversationCompletedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * InitiativeEventListener 单元测试。
 *
 * @author zsg
 * @since 2026-07-06
 */
class InitiativeEventListener_单元测试 {

    @Test
    void 关闭记忆学习时不生成主动想法() {
        var engine = mock(InitiativeEngine.class);
        var listener = new InitiativeEventListener(engine, Clock.systemUTC());

        listener.onConversationCompleted(new ConversationCompletedEvent(
                this,
                "user-1",
                "session-1",
                "turn-1",
                "用户要求不要写入长期记忆",
                false,
                "user_memory_write_denied"));

        verifyNoInteractions(engine);
    }
}
