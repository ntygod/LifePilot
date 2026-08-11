package com.lifepilot.agent.learning;

import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.conversation.event.ConversationCompletedEvent;
import com.lifepilot.interaction.web.service.ConversationSummaryGenerator;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * ConversationCompletionHook 单元测试。
 *
 * <p>旧主动引擎移除后钩子只保留摘要生成与画像巩固两项，隐式信号采集不再由此触发。</p>
 *
 * @author zsg
 * @since 2026-07-06
 */
class ConversationCompletionHook_单元测试 {

    @Test
    void 关闭记忆学习时跳过画像巩固但保留摘要生成() {
        var summaryGenerator = mock(ConversationSummaryGenerator.class);
        var userProfileConsolidator = mock(UserProfileConsolidator.class);
        var hook = new ConversationCompletionHook(summaryGenerator, userProfileConsolidator);

        hook.handleEvent(new ConversationCompletedEvent(
                this,
                "user-1",
                "session-1",
                "turn-1",
                "用户要求不要写入长期记忆",
                false,
                "user_memory_write_denied"));

        verify(summaryGenerator, timeout(1000)).generateIfNeeded("session-1");
        verify(userProfileConsolidator, after(200).never()).consolidate();
    }

    @Test
    void 开启记忆学习时摘要生成与画像巩固都执行() {
        var summaryGenerator = mock(ConversationSummaryGenerator.class);
        var userProfileConsolidator = mock(UserProfileConsolidator.class);
        var hook = new ConversationCompletionHook(summaryGenerator, userProfileConsolidator);

        hook.handleEvent(new ConversationCompletedEvent(
                this,
                "user-1",
                "session-2",
                "turn-2",
                "今天把项目排期定下来了",
                true,
                null));

        verify(summaryGenerator, timeout(1000)).generateIfNeeded("session-2");
        verify(userProfileConsolidator, timeout(1000)).consolidate();
    }

    @Test
    void 摘要生成抛异常不影响画像巩固() {
        var summaryGenerator = mock(ConversationSummaryGenerator.class);
        var userProfileConsolidator = mock(UserProfileConsolidator.class);
        org.mockito.Mockito.doThrow(new RuntimeException("摘要生成失败"))
                .when(summaryGenerator).generateIfNeeded("session-3");
        var hook = new ConversationCompletionHook(summaryGenerator, userProfileConsolidator);

        hook.handleEvent(new ConversationCompletedEvent(
                this,
                "user-1",
                "session-3",
                "turn-3",
                "摘要链路异常时仍应继续巩固画像",
                true,
                null));

        verify(userProfileConsolidator, timeout(1000)).consolidate();
    }
}
