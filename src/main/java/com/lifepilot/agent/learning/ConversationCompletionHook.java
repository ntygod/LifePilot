package com.lifepilot.agent.learning;

import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.conversation.event.ConversationCompletedEvent;
import com.lifepilot.interaction.web.service.ConversationSummaryGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.Objects;

/**
 * 对话完成钩子 — 在每轮对话结束后触发摘要生成和画像巩固。
 *
 * <p>原先还负责旧主动引擎的隐式信号采集（参与度检测 / 未命中检测）；
 * 旧引擎移除后仅保留记忆侧的两项收尾工作，主动性改由
 * {@code InitiativeEventListener} 基于同一事件独立驱动。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ConversationCompletionHook {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompletionHook.class);

    private final ConversationSummaryGenerator summaryGenerator;
    private final UserProfileConsolidator userProfileConsolidator;

    public ConversationCompletionHook(ConversationSummaryGenerator summaryGenerator,
                                      UserProfileConsolidator userProfileConsolidator) {
        this.summaryGenerator = Objects.requireNonNull(summaryGenerator, "对话摘要生成器不能为空");
        this.userProfileConsolidator = Objects.requireNonNull(userProfileConsolidator, "用户画像巩固器不能为空");
    }

    /** Spring 事件监听 — 自动响应 ConversationCompletedEvent。 */
    @EventListener
    public void handleEvent(ConversationCompletedEvent event) {
        Thread.ofVirtual().name("conversation-hook-" + event.getSessionId()).start(() -> {
            // 对话摘要生成
            try {
                summaryGenerator.generateIfNeeded(event.getSessionId());
            } catch (Exception e) {
                log.debug("对话完成钩子: 摘要生成跳过: {}", e.getMessage());
            }

            // 画像巩固（内置防抖，距上次不到 2 小时自动跳过）
            if (!event.isMemoryLearningEnabled()) {
                log.debug("对话完成钩子: 画像巩固已按本轮记忆边界跳过, sessionId={}, turnId={}, reason={}",
                        event.getSessionId(), event.getTurnId(), event.getMemoryLearningSkipReason());
                return;
            }
            try {
                userProfileConsolidator.consolidate();
            } catch (Exception e) {
                log.debug("对话完成钩子: 画像巩固跳过: {}", e.getMessage());
            }
        });
    }
}
