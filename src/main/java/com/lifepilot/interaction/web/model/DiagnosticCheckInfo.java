package com.lifepilot.interaction.web.model;

import java.util.Map;

/**
 * 本地诊断检查项。
 *
 * @param id       检查项 ID
 * @param label    展示名称
 * @param status   状态：OK / WARN / ERROR
 * @param detail   简短说明
 * @param metadata 附加只读信息
 * @author zsg
 * @since 2026-07-04
 */
public record DiagnosticCheckInfo(
        String id,
        String label,
        String status,
        String detail,
        Map<String, Object> metadata
) {
}
