package com.lifepilot.meta.infra.shell;

import org.springframework.context.ApplicationEvent;

/**
 * 后台进程输出事件 — 当进程产生新输出时发布，供 SSE 控制器监听并推送到前端。
 *
 * @author zsg
 * @since 2026-04-05
 */
public class ProcessOutputEvent extends ApplicationEvent {

    private final String sessionId;
    private final String channel;
    private final String content;
    private final ProcessState state;

    /**
     * 创建进程输出事件。
     *
     * @param source    事件来源
     * @param sessionId 进程 sessionId
     * @param channel   输出通道（"stdout" 或 "stderr"）
     * @param content   输出内容
     * @param state     当前进程状态
     */
    public ProcessOutputEvent(Object source, String sessionId, String channel,
                               String content, ProcessState state) {
        super(source);
        this.sessionId = sessionId;
        this.channel = channel;
        this.content = content;
        this.state = state;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getChannel() {
        return channel;
    }

    public String getContent() {
        return content;
    }

    public ProcessState getState() {
        return state;
    }
}
