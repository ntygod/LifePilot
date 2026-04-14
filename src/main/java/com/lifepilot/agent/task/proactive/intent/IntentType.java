package com.lifepilot.agent.task.proactive.intent;

/**
 * 意图类型。
 *
 * <p>"我想买耳机"是 GOAL，"帮我盯着降价"是 MONITORING，
 * "等降到300告诉我"是 CONDITIONAL，"每天跑步"是 RECURRING。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public enum IntentType {
    GOAL,
    MONITORING,
    CONDITIONAL,
    RECURRING
}
