package com.lifepilot.interaction.web.sse;

/**
 * SSE 事件类型常量类。
 * 
 * <p>统一管理所有 SSE 事件类型，避免硬编码字符串，提高类型安全性。</p>
 * 
 * <p>事件类型说明：</p>
 * <ul>
 *   <li>Chat 模块：{@link #TOKEN}, {@link #UI}, {@link #DONE}, {@link #ERROR}, {@link #HEARTBEAT}</li>
 *   <li>A2A 模块：{@link #TASK_STATUS_UPDATE}, {@link #TASK_ARTIFACT_UPDATE}, {@link #TASK_COMPLETE}</li>
 * </ul>
 * 
 * @author zsg
 * @since 2026-02-28
 */
public final class SseEventType {
    
    private SseEventType() {
        // 工具类，禁止实例化
    }
    
    // Chat 模块事件类型
    /** 增量文本片段事件 */
    public static final String TOKEN = "token";

    /** 推理过程事件（Reasoning Timeline） */
    public static final String REASONING = "reasoning";
    
    /** UI 组件更新事件 */
    public static final String UI = "ui";
    
    /** 消息完成事件 */
    public static final String DONE = "done";
    
    /** 错误事件 */
    public static final String ERROR = "error";
    
    /** 心跳事件 */
    public static final String HEARTBEAT = "heartbeat";

    // Trace 模块事件类型
    /** Trace 开始事件 */
    public static final String TRACE_START = "trace-start";

    /** Trace 步骤事件 */
    public static final String TRACE_STEP = "trace-step";

    /** Trace 结束事件 */
    public static final String TRACE_END = "trace-end";
    
    // A2A 模块事件类型
    /** 任务状态更新事件 */
    public static final String TASK_STATUS_UPDATE = "task-status-update";
    
    /** 任务产物更新事件 */
    public static final String TASK_ARTIFACT_UPDATE = "task-artifact-update";
    
    /** 任务完成事件 */
    public static final String TASK_COMPLETE = "task-complete";
}
