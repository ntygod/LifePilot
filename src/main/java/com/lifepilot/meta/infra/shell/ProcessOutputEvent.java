package com.lifepilot.meta.infra.shell;

import jakarta.annotation.Nullable;
import org.springframework.context.ApplicationEvent;

/**
 * 后台进程输出事件 — 当进程产生新输出或状态变化时发布，供 SSE 控制器监听并推送到前端。
 *
 * <p>{@code command} 字段仅在进程启动事件（channel=state, content=started）时携带，
 * 其他事件（增量输出、退出）为 null，避免冗余。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public class ProcessOutputEvent extends ApplicationEvent {

    private final String sessionId;
    private final String channel;
    private final String content;
    private final ProcessState state;
    @Nullable
    private final String command;

    /**
     * 创建进程输出事件（无 command 字段）。
     *
     * @param source    事件来源
     * @param sessionId 进程 sessionId
     * @param channel   输出通道（"stdout" / "stderr" / "state"）
     * @param content   输出内容或状态变化描述
     * @param state     当前进程状态
     */
    public ProcessOutputEvent(Object source, String sessionId, String channel,
                               String content, ProcessState state) {
        this(source, sessionId, channel, content, state, null);
    }

    /**
     * 创建进程输出事件（可携带 command 字段，用于启动事件）。
     *
     * @param source    事件来源
     * @param sessionId 进程 sessionId
     * @param channel   输出通道
     * @param content   输出内容
     * @param state     当前进程状态
     * @param command   原始启动命令，仅启动事件时填充
     */
    public ProcessOutputEvent(Object source, String sessionId, String channel,
                               String content, ProcessState state, @Nullable String command) {
        super(source);
        this.sessionId = sessionId;
        this.channel = channel;
        this.content = content;
        this.state = state;
        this.command = command;
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

    @Nullable
    public String getCommand() {
        return command;
    }
}
