package com.lifepilot.agent.initiative.model;

/**
 * 想法类型。
 *
 * @author zsg
 * @since 2026-06-01
 */
public enum ThoughtKind {
    /** 提醒：某件事快到期了、该做了。 */
    REMINDER,
    /** 追问：之前聊过的事，想知道进展。 */
    FOLLOW_UP,
    /** 洞察：发现了有价值的关联或模式。 */
    INSIGHT,
    /** 准备：即将到来的事件需要提前准备。 */
    PREPARATION,
    /** 关切：注意到用户可能遇到了问题。 */
    CONCERN,
    /** 建议：基于积累的了解，有一个建议想提。 */
    SUGGESTION
}
