package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.proactive.signal.ImplicitSignalCollector;
import com.lifepilot.interaction.web.service.ConversationSummaryGenerator;
import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 对话完成钩子 — 在每轮对话结束后触发隐式信号检测、摘要生成和画像巩固。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ConversationCompletionHook {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompletionHook.class);

    private final ImplicitSignalCollector implicitSignalCollector;
    private final ConversationSummaryGenerator summaryGenerator;
    private final UserProfileConsolidator userProfileConsolidator;

    public ConversationCompletionHook(ImplicitSignalCollector implicitSignalCollector,
                                      ConversationSummaryGenerator summaryGenerator,
                                      UserProfileConsolidator userProfileConsolidator) {
        this.implicitSignalCollector = Objects.requireNonNull(implicitSignalCollector, "隐式信号采集器不能为空");
        this.summaryGenerator = Objects.requireNonNull(summaryGenerator, "对话摘要生成器不能为空");
        this.userProfileConsolidator = Objects.requireNonNull(userProfileConsolidator, "用户画像巩固器不能为空");
    }

    /**
     * 对话完成后调用 — 触发隐式信号检测。
     *
     * @param userId              用户 ID
     * @param conversationSummary 对话摘要或最后几条消息
     */
    public void onConversationCompleted(String userId, @Nullable String conversationSummary) {
        if (conversationSummary == null) return;

        // 投递后参与检测（用户是否因通知而发起对话）
        try {
            implicitSignalCollector.onConversationCompleted(userId, conversationSummary);
        } catch (Exception e) {
            log.debug("对话完成钩子: 参与度检测跳过: {}", e.getMessage());
        }

        // 未命中检测（用户主动提了引擎该推但没推的）
        try {
            implicitSignalCollector.checkMissedOpportunities(userId, conversationSummary);
        } catch (Exception e) {
            log.debug("对话完成钩子: 未命中检测跳过: {}", e.getMessage());
        }

        log.debug("对话完成钩子已执行: userId={}", userId);
    }

    /** Spring 事件监听 — 自动响应 ConversationCompletedEvent。 */
    @EventListener
    public void handleEvent(ConversationCompletedEvent event) {
        Thread.ofVirtual().name("conversation-hook-" + event.getSessionId()).start(() -> {
            onConversationCompleted(event.getUserId(), event.getSummary());

            // 对话摘要生成
            try {
                summaryGenerator.generateIfNeeded(event.getSessionId());
            } catch (Exception e) {
                log.debug("对话完成钩子: 摘要生成跳过: {}", e.getMessage());
            }

            // 画像巩固（内置防抖，距上次不到 2 小时自动跳过）
            try {
                userProfileConsolidator.consolidate();
            } catch (Exception e) {
                log.debug("对话完成钩子: 画像巩固跳过: {}", e.getMessage());
            }
        });
    }
}
