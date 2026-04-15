package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 剪贴板意图缓冲区 — 线程安全队列，供 ClipboardBehavior 消费。
 *
 * <p>由 ContextController 写入，由 ClipboardBehavior.detect() 消费并清空。
 * 写入无界，消费时只取最近 {@value MAX_DRAIN} 条。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ClipboardIntentBuffer {

    private final ConcurrentLinkedQueue<ReminderClipboardIntent> queue = new ConcurrentLinkedQueue<>();
    private static final int MAX_DRAIN = 20;

    /** 写入一条意图。 */
    public void offer(ReminderClipboardIntent intent) {
        queue.offer(intent);
    }

    /** 消费并清空缓冲区，最多返回 {@value MAX_DRAIN} 条（最近写入的优先）。 */
    public List<ReminderClipboardIntent> drainAll() {
        var all = new ArrayList<ReminderClipboardIntent>();
        ReminderClipboardIntent item;
        while ((item = queue.poll()) != null) {
            all.add(item);
        }
        // 只保留最近 MAX_DRAIN 条
        if (all.size() > MAX_DRAIN) {
            return all.subList(all.size() - MAX_DRAIN, all.size());
        }
        return all;
    }

    public boolean isEmpty() { return queue.isEmpty(); }
}
