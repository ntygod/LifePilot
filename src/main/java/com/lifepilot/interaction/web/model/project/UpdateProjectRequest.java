package com.lifepilot.interaction.web.model.project;

/**
 * 更新项目请求。
 *
 * <p>{@code instructions} / {@code isolation} 为 null 时由 Service 判定为保留原值。
 * {@code name} 由 Service 做必填与重名校验。</p>
 *
 * @param name         新项目名
 * @param instructions 新指示词
 * @param isolation    新隔离模式字符串
 * @author zsg
 * @since 2026-04-23
 */
public record UpdateProjectRequest(
        String name,
        String instructions,
        String isolation
) {
}
