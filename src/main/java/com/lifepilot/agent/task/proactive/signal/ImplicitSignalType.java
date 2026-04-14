package com.lifepilot.agent.task.proactive.signal;

/**
 * 隐式信号类型。
 *
 * @author zsg
 * @since 2026-04-14
 */
public enum ImplicitSignalType {

    /** 投递后短时间内用户发起相关对话 — 强正信号。 */
    POST_DELIVERY_ENGAGEMENT,

    /** 投递后长时间无反应 — 弱负信号。 */
    POST_DELIVERY_IGNORE,

    /** 用户主动提出了本该引擎主动推送的内容 — 漏掉的机会。 */
    MISSED_OPPORTUNITY,

    /** 用户在某类话题上持续追问 — 高兴趣信号。 */
    SUSTAINED_INTEREST,

    /** 用户对话语气变短/变急 — 当前时机不好。 */
    BAD_TIMING
}
