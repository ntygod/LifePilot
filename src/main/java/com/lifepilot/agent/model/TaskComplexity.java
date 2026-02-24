package com.lifepilot.agent.model;

/**
 * 任务复杂度枚举。
 *
 * @author zsg
 * @since 2026-07-20
 */
public enum TaskComplexity {

    /** 单步或无需工具 */
    SIMPLE,

    /** 2-5 步 */
    MODERATE,

    /** 5+ 步或需要 SubAgent */
    COMPLEX
}
