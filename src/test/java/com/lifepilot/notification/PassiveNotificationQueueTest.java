package com.lifepilot.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link PassiveNotificationQueue} 单元测试。
 *
 * @author zsg
 * @since 2026-03-13
 */
@ExtendWith(MockitoExtension.class)
class PassiveNotificationQueueTest {

    @Mock private NotificationRepository notificationRepository;

    private PassiveNotificationQueue queue;

    @BeforeEach
    void setUp() {
        queue = new PassiveNotificationQueue(notificationRepository);
    }

    private PassiveQueueEntry testEntry(String id) {
        return new PassiveQueueEntry(id, "user-1", "alert", Urgency.LOW,
                "{\"text\":\"test\"}", false, Instant.now());
    }

    // ── enqueue ──────────────────────────────────────────

    @Nested
    class 入队 {

        @Test
        void enqueue_写入数据库并加入内存队列() {
            var entry = testEntry("e-1");

            queue.enqueue(entry);

            verify(notificationRepository).saveQueueEntry(entry);
            assertEquals(1, queue.size());
        }

        @Test
        void 数据库写入失败_仍加入内存队列() {
            doThrow(new RuntimeException("DB 写入失败"))
                    .when(notificationRepository).saveQueueEntry(any());

            var entry = testEntry("e-2");
            queue.enqueue(entry);

            assertEquals(1, queue.size());
        }
    }

    // ── drainAll ─────────────────────────────────────────

    @Nested
    class 取出 {

        @Test
        void drainAll_返回所有条目并标记已投递() {
            queue.enqueue(testEntry("e-1"));
            queue.enqueue(testEntry("e-2"));

            var drained = queue.drainAll();

            assertEquals(2, drained.size());
            assertEquals(0, queue.size());
            verify(notificationRepository).markEntriesDelivered(List.of("e-1", "e-2"));
        }

        @Test
        void drainAll_空队列返回空列表() {
            var drained = queue.drainAll();

            assertTrue(drained.isEmpty());
            verify(notificationRepository, never()).markEntriesDelivered(any());
        }
    }

    // ── loadUndelivered ──────────────────────────────────

    @Nested
    class 加载未投递 {

        @Test
        void loadUndelivered_从数据库加载到内存队列() {
            when(notificationRepository.findUndeliveredEntries())
                    .thenReturn(List.of(testEntry("e-1"), testEntry("e-2")));

            queue.loadUndelivered();

            assertEquals(2, queue.size());
        }

        @Test
        void loadUndelivered_数据库异常不抛出() {
            when(notificationRepository.findUndeliveredEntries())
                    .thenThrow(new RuntimeException("DB 不可用"));

            assertDoesNotThrow(() -> queue.loadUndelivered());
            assertEquals(0, queue.size());
        }
    }
}
