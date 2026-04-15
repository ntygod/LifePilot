package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 信任状态 DTO — 返回给前端的行为自主度信息。
 *
 * @param behaviorName       行为插件名称
 * @param behaviorLabel      行为中文标签
 * @param currentLevel       当前自主度级别（"A" / "B" / "C"）
 * @param targetLevel        下一级自主度（C 级返回 null）
 * @param upgradeSuggested   是否建议升级
 * @param consecutivePositive 连续正反馈次数
 * @param consecutiveNegative 连续负反馈次数
 * @param cooldownUntil      冷却截止时间
 * @param updatedAt          更新时间
 * @author zsg
 * @since 2026-04-15
 */
public record TrustStatusDto(
        String behaviorName,
        String behaviorLabel,
        String currentLevel,
        @Nullable String targetLevel,
        boolean upgradeSuggested,
        int consecutivePositive,
        int consecutiveNegative,
        @Nullable Instant cooldownUntil,
        Instant updatedAt
) {}
