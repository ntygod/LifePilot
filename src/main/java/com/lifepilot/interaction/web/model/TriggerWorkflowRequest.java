package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 手动触发工作流请求。
 *
 * @param inputs 工作流输入参数（可选）
 * @author zsg
 * @since 2026-02-27
 */
public record TriggerWorkflowRequest(
        @Nullable Map<String, Object> inputs
) {
}
