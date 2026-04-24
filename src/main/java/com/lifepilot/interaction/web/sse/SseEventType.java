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
 *   <li>MCP 模块：{@link #MCP_STATUS_SNAPSHOT}, {@link #MCP_STATUS_CHANGE}</li>
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

    /** 主动通知事件 */
    public static final String NOTIFICATION = "notification";

    public static final String INTERACTION = "interaction";

    // MCP 模块事件类型
    /** MCP Server 状态初始快照事件 */
    public static final String MCP_STATUS_SNAPSHOT = "mcp-status-snapshot";

    /** MCP Server 状态变化事件 */
    public static final String MCP_STATUS_CHANGE = "mcp-status-change";

    // Workflow 模块事件类型
    /** 工作流执行列表快照事件 */
    public static final String WORKFLOW_EXECUTIONS_SNAPSHOT = "workflow-executions-snapshot";

    /** 工作流执行实例快照事件 */
    public static final String WORKFLOW_EXECUTION_SNAPSHOT = "workflow-execution-snapshot";

    /** 工作流执行实例更新事件 */
    public static final String WORKFLOW_EXECUTION_UPDATED = "workflow-execution-updated";

    /** 工作流事件时间线快照事件 */
    public static final String WORKFLOW_TIMELINE_SNAPSHOT = "workflow-timeline-snapshot";

    /** 工作流事件新增事件 */
    public static final String WORKFLOW_EVENT_CREATED = "workflow-event-created";

    /** 工作流步骤日志快照事件 */
    public static final String WORKFLOW_STEP_LOGS_SNAPSHOT = "workflow-step-logs-snapshot";

    /** 工作流步骤日志新增事件 */
    public static final String WORKFLOW_STEP_LOG_CREATED = "workflow-step-log-created";

    // 媒体模块事件类型
    /** 媒体数据事件（图片/音频等大体积二进制数据） */
    public static final String MEDIA = "media";

    /** 语音转录结果事件 */
    public static final String TRANSCRIPTION = "transcription";

    // 护栏模块事件类型
    /** 权限审批请求事件 */
    public static final String PERMISSION_APPROVAL_REQUEST = "permission-approval-request";

    // 会话模块事件类型
    /** 会话标题自动生成事件 */
    public static final String TITLE_GENERATED = "title-generated";

    // Agent 挂起-恢复事件类型
    /** Agent 挂起事件 */
    public static final String AGENT_SUSPENDED = "agent-suspended";

    /** Agent 恢复事件 */
    public static final String AGENT_RESUMED = "agent-resumed";

    // Process 模块事件类型
    /** 后台进程输出事件 */
    public static final String PROCESS_OUTPUT = "process-output";

    /** 后台进程状态变化事件 */
    public static final String PROCESS_STATE_CHANGE = "process-state-change";

    /** 后台进程启动事件 */
    public static final String PROCESS_STARTED = "process-started";

    /** 后台进程初始快照事件（连接建立时推送当前所有活跃进程） */
    public static final String PROCESS_SNAPSHOT = "process-snapshot";

    // Skill 模块事件类型
    /** Skill 自动生成完成事件（SkillSynthesizer 落库 + 校验通过后广播） */
    public static final String SKILL_GENERATED = "skill-generated";
}
