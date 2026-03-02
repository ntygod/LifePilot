package com.lifepilot.conversation;

import java.util.List;
import java.util.Optional;

/**
 * 会话视图服务接口。
 *
 * <p>
 * 提供与底层会话存储解耦的统一读取入口，供记忆系统、评估组件等下游模块使用。
 * 不承担写入职责。
 * </p>
 */
public interface ConversationViewService {

    /**
     * 按会话 ID 获取会话视图。
     *
     * @param sessionId 会话 ID
     * @return 会话视图，不存在时返回 {@link Optional#empty()}
     */
    Optional<ConversationSessionView> getSession(String sessionId);

    /**
     * 按会话 ID 获取最近若干条对话消息视图。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数（按时间倒序截断后再按时间正序返回）
     * @return 轮次视图列表
     */
    List<ConversationTurnView> getRecentTurns(String sessionId, int limit);

    /**
     * 按会话 ID 获取完整时间线视图。
     *
     * <p>
     * 当前实现优先从 L2 {@code EpisodicMemory} 读取完整历史；
     * 若该会话尚未归档到 L2，则退化为基于 {@code SessionSnapshot.recentTurns} 的最近 N 条消息视图。
     * </p>
     *
     * @param sessionId 会话 ID
     * @return 轮次视图列表（按时间正序）
     */
    List<ConversationTurnView> getFullTimeline(String sessionId);
}

