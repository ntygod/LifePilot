package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.agent.task.proactive.signal.ImplicitSignalCollector;
import com.lifepilot.interaction.web.service.ConversationSummaryGenerator;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * ConversationCompletionHook 单元测试。
 *
 * @author zsg
 * @since 2026-07-06
 */
class ConversationCompletionHook_单元测试 {

    @Test
    void 关闭记忆学习时跳过隐式信号和画像巩固但保留摘要生成() {
        var implicitSignalCollector = mock(ImplicitSignalCollector.class);
        var summaryGenerator = mock(ConversationSummaryGenerator.class);
        var userProfileConsolidator = mock(UserProfileConsolidator.class);
        var hook = new ConversationCompletionHook(
                implicitSignalCollector,
                summaryGenerator,
                userProfileConsolidator);

        hook.handleEvent(new ConversationCompletedEvent(
                this,
                "user-1",
                "session-1",
                "turn-1",
                "用户要求不要写入长期记忆",
                false,
                "user_memory_write_denied"));

        verify(summaryGenerator, timeout(1000)).generateIfNeeded("session-1");
        verify(implicitSignalCollector, after(200).never()).onConversationCompleted(anyString(), anyString());
        verify(implicitSignalCollector, after(200).never()).checkMissedOpportunities(anyString(), anyString());
        verify(userProfileConsolidator, after(200).never()).consolidate();
    }
}
