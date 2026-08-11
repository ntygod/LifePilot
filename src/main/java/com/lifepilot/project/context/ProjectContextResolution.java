package com.lifepilot.project.context;

import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 项目上下文解析结果。
 *
 * <p>用于区分“确实没有项目上下文”和“会话带项目但解析失败”。后者必须让记忆读写 fail-closed，
 * 避免在隔离项目场景下退回主账户空间造成污染。</p>
 *
 * @author zsg
 * @since 2026-06-18
 */
public record ProjectContextResolution(
        @Nullable ProjectContext context,
        boolean failed,
        @Nullable String reason
) {

    public ProjectContextResolution {
        if (failed) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("项目上下文解析失败原因不能为空");
            }
        } else {
            Objects.requireNonNull(context, "项目上下文不能为空");
        }
    }

    public static ProjectContextResolution resolved(ProjectContext context) {
        return new ProjectContextResolution(Objects.requireNonNull(context, "项目上下文不能为空"), false, null);
    }

    public static ProjectContextResolution failed(String reason) {
        return new ProjectContextResolution(null, true, reason);
    }

    @Override
    public ProjectContext context() {
        if (failed || context == null) {
            throw new IllegalStateException("项目上下文解析失败: " + reason);
        }
        return context;
    }
}
