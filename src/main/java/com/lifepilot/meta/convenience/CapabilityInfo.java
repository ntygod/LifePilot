package com.lifepilot.meta.convenience;

/**
 * 单个能力的信息 — 统一描述 Skill / Agent / Tool / Workflow / MCP 的摘要。
 *
 * @param id          能力唯一标识
 * @param name        能力显示名称
 * @param description 能力描述
 * @param source      来源标识（"builtin" / "marketplace" / "mcp" / "yaml" / "auto-generated"）
 * @param status      状态（"active" / "inactive" / "error"）
 * @param type        类型（"skill" / "agent" / "tool" / "workflow" / "mcp"）
 * @author zsg
 * @since 2026-03-08
 */
public record CapabilityInfo(
        String id,
        String name,
        String description,
        String source,
        String status,
        String type
) {}
