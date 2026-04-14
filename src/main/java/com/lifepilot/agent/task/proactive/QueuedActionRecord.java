package com.lifepilot.agent.task.proactive;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 排队动作记录。
 *
 * @param id        唯一标识
 * @param userId    用户 ID
 * @param behavior  产出此动作的行为插件名称
 * @param topicKey  主题键
 * @param title     主题标题
 * @param content   消息内容
 * @param score     候选分数
 * @param metadata  扩展元数据 JSON
 * @param shown     是否已展示
 * @param createdAt 创建时间
 * @param shownAt   展示时间
 * @author zsg
 * @since 2026-04-14
 */
public record QueuedActionRecord(
        String id,
        String userId,
        String behavior,
        String topicKey,
        String title,
        String content,
        float score,
        @Nullable String metadata,
        boolean shown,
        Instant createdAt,
        @Nullable Instant shownAt
) {
    public QueuedActionRecord {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(behavior, "behavior 不能为空");
        Objects.requireNonNull(topicKey, "topicKey 不能为空");
        Objects.requireNonNull(title, "title 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }
}
