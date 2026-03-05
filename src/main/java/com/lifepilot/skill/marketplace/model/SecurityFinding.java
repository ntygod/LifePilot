package com.lifepilot.skill.marketplace.model;

/**
 * 单条安全扫描发现。
 *
 * @param level       风险级别
 * @param category    发现类别（如"危险工具"、"Prompt 注入"）
 * @param description 人类可读的中文描述
 * @author zsg
 * @since 2026-03-05
 */
public record SecurityFinding(
        RiskLevel level,
        String category,
        String description
) {}
