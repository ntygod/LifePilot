package com.lifepilot.interaction.web.model.project;

/**
 * 创建项目请求。
 *
 * @param name         项目名（必填，1..64 字符，同账户唯一）
 * @param instructions 指示词（可选，传 null 时视为空串）
 * @param isolation    隔离模式字符串（可选，传 null 时使用默认 ISOLATED）
 * @author zsg
 * @since 2026-04-23
 */
public record CreateProjectRequest(
        String name,
        String instructions,
        String isolation
) {
}
