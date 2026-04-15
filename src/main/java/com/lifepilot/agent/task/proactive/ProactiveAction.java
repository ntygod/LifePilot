package com.lifepilot.agent.task.proactive;

import java.util.Objects;

/**
 * 主动行为动作 — 由行为插件的 reason() 产出。
 *
 * @param candidate      源候选
 * @param content        生成的通知/消息内容
 * @param suggestedLevel 插件建议的投递级别（DecisionGate 可降级但不升级）
 * @param detail         插件专属投递数据（元信息、策略追踪等）
 * @author zsg
 * @since 2026-04-14
 */
public record ProactiveAction(
        ProactiveCandidate candidate,
        String content,
        DeliveryLevel suggestedLevel,
        Object detail
) {
    public ProactiveAction {
        Objects.requireNonNull(candidate, "candidate 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(suggestedLevel, "suggestedLevel 不能为空");
    }
}
