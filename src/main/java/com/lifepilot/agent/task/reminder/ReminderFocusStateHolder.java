package com.lifepilot.agent.task.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 焦点状态持有器 — 以内存 AtomicReference 保存最新桌面焦点状态。
 *
 * <p>由 Tauri 端通过 REST 端点上报，供 {@link ProactiveReminderService}
 * 构建运行时上下文时读取。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public class ReminderFocusStateHolder {

    private static final Logger log = LoggerFactory.getLogger(ReminderFocusStateHolder.class);

    private final AtomicReference<ReminderFocusState> latest = new AtomicReference<>();

    /**
     * 更新最新焦点状态。
     */
    public void update(ReminderFocusState state) {
        latest.set(state);
        log.debug("焦点状态更新: app={}, title={}, fullscreen={}, idle={}min",
                state.focusApp(), state.focusTitle(), state.fullscreen(), state.idleMinutes());
    }

    /**
     * 获取最新焦点状态，可能为 null（桌面端尚未上报时）。
     */
    @Nullable
    public ReminderFocusState get() {
        return latest.get();
    }
}
