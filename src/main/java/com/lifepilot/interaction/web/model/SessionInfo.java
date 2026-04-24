package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 会话摘要信息。
 *
 * <p>projectId 语义：</p>
 * <ul>
 *   <li>{@code null} —— 归属主账户（侧栏"今天/昨天"分组只显示此类会话）</li>
 *   <li>非空 —— 归属具体项目，仅在对应项目下展示</li>
 * </ul>
 *
 * @param id               会话 ID
 * @param title            会话标题（取首条消息摘要）
 * @param createdAt        创建时间
 * @param updatedAt        最后更新时间
 * @param pinned           是否置顶
 * @param archived         是否归档
 * @param lastMessagePreview 最近消息预览
 * @param lastMessageAt    最近一次消息时间
 * @param projectId        归属项目 ID（主账户会话为 {@code null}）
 * @author zsg
 * @since 2026-02-27
 */
public record SessionInfo(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Boolean pinned,
        Boolean archived,
        String lastMessagePreview,
        Instant lastMessageAt,
        @Nullable String projectId
) {}
