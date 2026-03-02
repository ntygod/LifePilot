package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 批量更新请求。
 *
 * @param action    操作类型（"pin", "unpin", "archive", "unarchive", "delete"）
 * @param sessionIds 会话 ID 列表
 */
public record BatchUpdateRequest(
        String action,
        List<String> sessionIds
) {}
