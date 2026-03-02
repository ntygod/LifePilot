package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 更新知识库请求。
 *
 * @param description 知识库描述（可选）
 * @param tags        标签列表（可选）
 * @author zsg
 * @since 2026-02-27
 */
public record UpdateKbRequest(
        @Nullable String description,
        @Nullable List<String> tags
) {
}
