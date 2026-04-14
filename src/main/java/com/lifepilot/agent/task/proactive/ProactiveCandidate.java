package com.lifepilot.agent.task.proactive;

import java.util.Objects;

/**
 * 主动候选 — 由行为插件的 detect() 产出。
 *
 * @param id           唯一标识
 * @param behaviorName 产出此候选的行为插件名称
 * @param topicKey     主题键
 * @param title        主题标题
 * @param score        综合评分 [0, 1]
 * @param rationale    候选理由（面向日志/调试）
 * @param detail       插件专属数据（框架不解读，插件在 reason 阶段取回使用）
 * @author zsg
 * @since 2026-04-14
 */
public record ProactiveCandidate(
        String id,
        String behaviorName,
        String topicKey,
        String title,
        float score,
        String rationale,
        Object detail
) {
    public ProactiveCandidate {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(behaviorName, "behaviorName 不能为空");
        Objects.requireNonNull(topicKey, "topicKey 不能为空");
        Objects.requireNonNull(title, "title 不能为空");
        rationale = Objects.requireNonNullElse(rationale, "");
        score = Math.max(0f, Math.min(1f, score));
    }
}
