package com.lifepilot.notification;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.notification.config.NotificationProperties;
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
 * {@link NotificationScheduler} 单元测试。
 *
 * <p>直接调用 {@code drain()} 方法验证行为，不启动定时调度。
 *
 * @author zsg
 * @since 2026-03-13
 */
@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {

    @Mock private PassiveNotificationQueue passiveNotificationQueue;
    @Mock private SseSessionManager sseSessionManager;
    @Mock private SharedScheduler sharedScheduler;

    private NotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        properties.setPassiveDrainInterval(60);
        when(sharedScheduler.cleanup()).thenReturn(
                java.util.concurrent.Executors.newScheduledThreadPool(1));
    }

    private PassiveQueueEntry testEntry(String id) {
        return new PassiveQueueEntry(id, "user-1", null, Urgency.LOW,
                "{\"text\":\"test\"}", false, Instant.now());
    }

    @Nested
    class Drain执行 {

        @Test
        void drain_调用drainAll并通过SseSessionManager广播() {
            var entries = List.of(testEntry("e-1"), testEntry("e-2"));
            when(passiveNotificationQueue.drainAll()).thenReturn(entries);

            var scheduler = new NotificationScheduler(passiveNotificationQueue, sseSessionManager, properties, sharedScheduler);
            scheduler.drain();

            verify(passiveNotificationQueue).drainAll();
            verify(sseSessionManager, times(2)).broadcastNotification(any());
        }

        @Test
        void drain_空队列不广播() {
            when(passiveNotificationQueue.drainAll()).thenReturn(List.of());

            var scheduler = new NotificationScheduler(passiveNotificationQueue, sseSessionManager, properties, sharedScheduler);
            scheduler.drain();

            verify(passiveNotificationQueue).drainAll();
            verify(sseSessionManager, never()).broadcastNotification(any());
        }

        @Test
        void drain_异常不崩溃() {
            when(passiveNotificationQueue.drainAll()).thenThrow(new RuntimeException("模拟异常"));

            var scheduler = new NotificationScheduler(passiveNotificationQueue, sseSessionManager, properties, sharedScheduler);

            assertDoesNotThrow(scheduler::drain);
        }

        @Test
        void drain_SseSessionManager为null时跳过广播() {
            var entries = List.of(testEntry("e-1"));
            when(passiveNotificationQueue.drainAll()).thenReturn(entries);

            var scheduler = new NotificationScheduler(passiveNotificationQueue, null, properties, sharedScheduler);
            scheduler.drain();

            verify(passiveNotificationQueue).drainAll();
            // 无 sseSessionManager，不应抛异常
        }
    }
}
