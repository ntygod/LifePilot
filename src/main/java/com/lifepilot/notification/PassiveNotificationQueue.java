package com.lifepilot.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 持久化被动通知队列。
 *
 * <p>内部使用 {@link ConcurrentLinkedQueue} 保证线程安全，
 * 同时将条目持久化到 {@code passive_notification_queue} 数据库表。
 * 启动时从数据库加载未投递记录到内存队列。
 *
 * @author zsg
 * @since 2026-03-13
 */
public class PassiveNotificationQueue {

    private static final Logger log = LoggerFactory.getLogger(PassiveNotificationQueue.class);

    private final ConcurrentLinkedQueue<PassiveQueueEntry> queue = new ConcurrentLinkedQueue<>();
    private final NotificationRepository notificationRepository;

    public PassiveNotificationQueue(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * 入队：写入数据库 + 加入内存队列。
     *
     * <p>数据库写入失败时记录 WARN 日志但不阻塞入队操作。
     *
     * @param entry 队列条目
     */
    public void enqueue(PassiveQueueEntry entry) {
        try {
            notificationRepository.saveQueueEntry(entry);
        } catch (Exception e) {
            log.warn("被动队列条目持久化失败，仅保留内存队列: id={}", entry.id(), e);
        }
        queue.add(entry);
        log.debug("被动队列入队: id={}, userId={}", entry.id(), entry.userId());
    }

    /**
     * 取出所有条目并更新数据库 delivered 状态。
     *
     * @return 取出的条目列表
     */
    public List<PassiveQueueEntry> drainAll() {
        var drained = new ArrayList<PassiveQueueEntry>();
        PassiveQueueEntry entry;
        while ((entry = queue.poll()) != null) {
            drained.add(entry);
        }
        if (!drained.isEmpty()) {
            var ids = drained.stream().map(PassiveQueueEntry::id).toList();
            try {
                notificationRepository.markEntriesDelivered(ids);
            } catch (Exception e) {
                log.warn("被动队列批量标记已投递失败: count={}", ids.size(), e);
            }
            log.debug("被动队列 drain 完成: count={}", drained.size());
        }
        return List.copyOf(drained);
    }

    /**
     * 启动时从数据库加载未投递记录到内存队列。
     */
    public void loadUndelivered() {
        try {
            var entries = notificationRepository.findUndeliveredEntries();
            queue.addAll(entries);
            log.info("被动队列加载未投递记录: count={}", entries.size());
        } catch (Exception e) {
            log.warn("被动队列加载未投递记录失败", e);
        }
    }

    /**
     * 获取当前内存队列大小（用于监控和测试）。
     *
     * @return 队列大小
     */
    public int size() {
        return queue.size();
    }
}
