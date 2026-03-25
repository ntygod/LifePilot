package com.lifepilot.agent.model;

/**
 * Agent 请求任务模式。
 *
 * <p>ANSWER 表示普通问答；EXECUTION 表示需要持续执行、验证或落地操作的任务。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public enum AgentTaskMode {
    AUTO,
    ANSWER,
    EXECUTION
}
