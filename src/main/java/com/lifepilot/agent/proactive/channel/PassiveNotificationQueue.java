package com.lifepilot.agent.proactive.channel;

import com.lifepilot.agent.proactive.model.ProactiveNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 被动通知队列 — 存储 LOW 紧急度通知，等待用户主动查看。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class PassiveNotificationQueue {

    private final ConcurrentLinkedQueue<ProactiveNotification> queue = new ConcurrentLinkedQueue<>();

    /** 入队通知。 */
    public void enqueue(ProactiveNotification notification) {
        queue.add(notification);
    }

    /**
     * 取出并清空队列中所有通知。
     *
     * @return 队列中的所有通知
     */
    public List<ProactiveNotification> drainAll() {
        var result = new ArrayList<ProactiveNotification>();
        ProactiveNotification item;
        while ((item = queue.poll()) != null) {
            result.add(item);
        }
        return List.copyOf(result);
    }
}
