package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动提醒主题别名映射记录。
 *
 * <p>用于把弱来源的临时 topic key 归并到稳定 canonical topic key，
 * 让同一件事跨会话、跨来源可以持续累积历史状态。</p>
 *
 * @param userId            用户 ID
 * @param aliasTopicKey     原始别名 topic key
 * @param canonicalTopicKey 归并后的 canonical topic key
 * @param topicFamily       主题族
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 * @author zsg
 * @since 2026-03-29
 */
public record ReminderTopicAliasRecord(
        String userId,
        String aliasTopicKey,
        String canonicalTopicKey,
        @Nullable String topicFamily,
        Instant createdAt,
        Instant updatedAt
) {
}
