package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 主动提醒策略版本记录。
 *
 * <p>用于持久化每一版经过调优后的策略参数，
 * 让执行轮次和决策记录能够追溯“当时采用的是哪版策略”。</p>
 *
 * @param id              版本记录 ID
 * @param userId          用户 ID
 * @param version         版本号
 * @param configSignature 配置签名
 * @param configJson      策略配置快照
 * @param source          版本来源
 * @param summaryJson     生成该版本时使用的反馈/回放摘要
 * @param activatedAt     首次启用时间
 * @param createdAt       创建时间
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderPolicyVersionRecord(
        String id,
        String userId,
        int version,
        String configSignature,
        String configJson,
        String source,
        @Nullable String summaryJson,
        Instant activatedAt,
        Instant createdAt
) {
}
