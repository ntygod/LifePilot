package com.lifepilot.agent.model;

/**
 * Agent 恢复策略。
 *
 * @author zsg
 * @since 2026-03-21
 */
public enum ResumePolicy {
    /**
     * 自动恢复：优先认领同任务 checkpoint，未命中则全新执行。
     */
    AUTO,

    /**
     * 全新执行：跳过并清理同任务 checkpoint。
     */
    FRESH
}
