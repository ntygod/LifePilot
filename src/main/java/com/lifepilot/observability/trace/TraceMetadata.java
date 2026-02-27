package com.lifepilot.observability.trace;

import jakarta.annotation.Nullable;

import java.util.Map;

/**
 * 追踪元数据 — 记录 Trace 的上下文信息。
 *
 * @param channelType   渠道类型（如 web、cli、wechat 等）
 * @param userId        用户 ID
 * @param clientVersion 客户端版本
 * @param tags          自定义标签
 * @author zsg
 * @since 2026-02-27
 */
public record TraceMetadata(
        @Nullable String channelType,
        @Nullable String userId,
        @Nullable String clientVersion,
        Map<String, String> tags
) {

    /**
     * 紧凑构造函数 — 使用 Map.copyOf() 保证不可变性。
     */
    public TraceMetadata {
        tags = Map.copyOf(tags);
    }
}
