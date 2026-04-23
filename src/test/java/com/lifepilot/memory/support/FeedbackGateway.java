package com.lifepilot.memory.support;

import com.lifepilot.memory.feedback.FeedbackProcessor;

/**
 * 场景测试用反馈网关 — 绕过 HTTP Controller 直接调用 {@link FeedbackProcessor}，
 * 在测试 DSL 中以中文方法名（点赞/点踩/无效提醒）表达用户反馈动作。
 *
 * <p>反馈类型按 {@link FeedbackProcessor#processFeedbackForEntry} 的字符串约定：
 * {@code "like"} / {@code "dislike"}（配置来自 {@code MemoryProperties.Feedback}）。</p>
 *
 * <p>本类非 Spring bean — 由 {@link com.lifepilot.memory.scenarios 场景测试基类} 直接
 * new 构造，避免让 ScenarioTestConfiguration 多承担一个可选 bean 的装配责任。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class FeedbackGateway {

    /** 与 {@link FeedbackProcessor} 约定的反馈类型常量。 */
    public static final String FEEDBACK_LIKE = "like";
    public static final String FEEDBACK_DISLIKE = "dislike";

    private final FeedbackProcessor feedbackProcessor;

    public FeedbackGateway(FeedbackProcessor feedbackProcessor) {
        this.feedbackProcessor = java.util.Objects.requireNonNull(
                feedbackProcessor, "feedbackProcessor");
    }

    /**
     * 对助手 transcript 条目提交点踩 — 会触发注入记忆的 importanceScore 扣减。
     *
     * @param assistantEntryId 助手 transcript 条目 ID
     */
    public void dislike(String assistantEntryId) {
        feedbackProcessor.processFeedbackForEntry(assistantEntryId, FEEDBACK_DISLIKE);
    }

    /**
     * 对助手 transcript 条目提交点赞 — 会触发注入记忆的 importanceScore 加成。
     *
     * @param assistantEntryId 助手 transcript 条目 ID
     */
    public void like(String assistantEntryId) {
        feedbackProcessor.processFeedbackForEntry(assistantEntryId, FEEDBACK_LIKE);
    }

    /**
     * 主动提醒"无效"反馈 — 对应前端"这条提醒没用"按钮，走 {@code PUT
     * /api/notifications/{id}/feedback}，最终由 Task 25 的 TrustUpgradeService
     * 将反馈溯源到触发规则的 Episodic/Semantic 记忆并调整 importance。
     *
     * <p>当前 Phase 0 未引入 TrustUpgradeService，保留占位；真实 DSL 方法会在 Task 25
     * 接入后替换此实现。</p>
     *
     * @param notificationId 通知 ID
     * @throws UnsupportedOperationException Phase 0 阶段未实现
     */
    public void notHelpful(String notificationId) {
        throw new UnsupportedOperationException(
                "notHelpful 占位：待 Task 25 TrustUpgradeService 反馈溯源接入后实现。"
                        + " notificationId=" + notificationId);
    }
}
