package com.lifepilot.skill.validation;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 三重验证管线结果。
 *
 * @param passed      是否通过
 * @param failedStage 失败阶段（通过时为 null）
 * @param errors      错误信息列表
 * @author zsg
 * @since 2026-02-25
 */
public record SkillValidationResult(
        boolean passed,
        @Nullable ValidationStage failedStage,
        List<String> errors
) {
    /** 防御性拷贝。 */
    public SkillValidationResult {
        errors = List.copyOf(errors);
    }

    /**
     * 创建验证通过结果。
     *
     * @return 通过结果
     */
    public static SkillValidationResult allPassed() {
        return new SkillValidationResult(true, null, List.of());
    }

    /**
     * 创建验证失败结果。
     *
     * @param stage  失败阶段
     * @param errors 错误信息列表
     * @return 失败结果
     */
    public static SkillValidationResult failed(ValidationStage stage, List<String> errors) {
        return new SkillValidationResult(false, stage, errors);
    }

    /**
     * 验证阶段枚举 — 格式、安全、沙箱。
     */
    public enum ValidationStage {
        /** 格式验证阶段。 */
        FORMAT,
        /** 安全验证阶段。 */
        SECURITY,
        /** 沙箱验证阶段。 */
        SANDBOX
    }
}
