package com.lifepilot.agent.proactive.model;

/**
 * 主动介入类型 — 定义候选的触达方式。
 *
 * <p>当前支持 {@link #NOTIFICATION} 和 {@link #PASSIVE_HINT} 两种方式，
 * 未来可扩展 WORKFLOW_RECOMMENDATION、DRAFT_GENERATION 等非通知类触达。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public enum InitiativeType {

    /** 主动通知 — 按紧急度分发到通道或被动队列。 */
    NOTIFICATION,

    /** 被动提示 — 下次聊天时顺带提醒。 */
    PASSIVE_HINT
}
