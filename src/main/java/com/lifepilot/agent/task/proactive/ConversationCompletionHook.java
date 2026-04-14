package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.proactive.intent.IntentMemoryService;
import com.lifepilot.agent.task.proactive.profile.UserProfileService;
import com.lifepilot.agent.task.proactive.signal.ImplicitSignalCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;

/**
 * 对话完成钩子 — 在每轮对话结束后触发画像巩固、隐式信号检测和未命中检测。
 *
 * <p>由对话管线（如 ReactAgentLoop 或 SSE 端点）在对话结束时调用。
 * 这是三个认知闭环的"最后一公里"接线。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ConversationCompletionHook {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompletionHook.class);

    @Nullable private final UserProfileService userProfileService;
    @Nullable private final ImplicitSignalCollector implicitSignalCollector;
    @Nullable private final IntentMemoryService intentMemoryService;

    public ConversationCompletionHook(@Nullable UserProfileService userProfileService,
                                      @Nullable ImplicitSignalCollector implicitSignalCollector,
                                      @Nullable IntentMemoryService intentMemoryService) {
        this.userProfileService = userProfileService;
        this.implicitSignalCollector = implicitSignalCollector;
        this.intentMemoryService = intentMemoryService;
    }

    /**
     * 对话完成后调用 — 触发所有认知闭环。
     *
     * @param userId              用户 ID
     * @param conversationSummary 对话摘要或最后几条消息
     */
    public void onConversationCompleted(String userId, @Nullable String conversationSummary) {
        // 1. 画像巩固检查（每 10 轮或每周触发 LLM 更新画像）
        if (userProfileService != null) {
            try {
                userProfileService.onConversationCompleted(userId);
            } catch (Exception e) {
                log.debug("对话完成钩子: 画像巩固跳过: {}", e.getMessage());
            }
        }

        // 2. 隐式信号 — 投递后参与检测（用户是否因通知而发起对话）
        if (implicitSignalCollector != null && conversationSummary != null) {
            try {
                implicitSignalCollector.onConversationCompleted(userId, conversationSummary);
            } catch (Exception e) {
                log.debug("对话完成钩子: 参与度检测跳过: {}", e.getMessage());
            }
        }

        // 3. 隐式信号 — 未命中检测（用户主动提了引擎该推但没推的）
        if (implicitSignalCollector != null && conversationSummary != null) {
            try {
                implicitSignalCollector.checkMissedOpportunities(userId, conversationSummary, intentMemoryService);
            } catch (Exception e) {
                log.debug("对话完成钩子: 未命中检测跳过: {}", e.getMessage());
            }
        }

        log.debug("对话完成钩子已执行: userId={}", userId);
    }

    /** Spring 事件监听 — 自动响应 ConversationCompletedEvent。 */
    @EventListener
    public void handleEvent(ConversationCompletedEvent event) {
        Thread.ofVirtual().name("conversation-hook-" + event.getSessionId()).start(() ->
                onConversationCompleted(event.getUserId(), event.getSummary()));
    }
}
