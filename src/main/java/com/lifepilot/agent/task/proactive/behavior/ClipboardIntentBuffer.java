package com.lifepilot.agent.task.proactive.behavior;

import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 剪贴板意图缓冲区 — 线程安全队列，供 ClipboardBehavior 消费。
 *
 * <p>由 ContextController 写入，由 ClipboardBehavior.detect() 消费并清空。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ClipboardIntentBuffer {

    private final ConcurrentLinkedQueue<ReminderClipboardIntent> queue = new ConcurrentLinkedQueue<>();
    private static final int MAX_SIZE = 20;

    /** 写入一条意图。 */
    public void offer(ReminderClipboardIntent intent) {
        queue.offer(intent);
        while (queue.size() > MAX_SIZE) queue.poll();
    }

    /** 消费并清空缓冲区。 */
    public List<ReminderClipboardIntent> drainAll() {
        var result = new ArrayList<ReminderClipboardIntent>();
        ReminderClipboardIntent item;
        while ((item = queue.poll()) != null) result.add(item);
        return result;
    }

    public boolean isEmpty() { return queue.isEmpty(); }
}
