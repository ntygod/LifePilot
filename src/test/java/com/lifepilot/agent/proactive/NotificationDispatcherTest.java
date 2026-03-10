package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.channel.NotificationChannel;
import com.lifepilot.agent.proactive.channel.PassiveNotificationQueue;
import com.lifepilot.agent.proactive.model.InitiativeType;
import com.lifepilot.agent.proactive.model.ProactiveNotification;
import com.lifepilot.agent.proactive.model.Urgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * NotificationDispatcher 单元测试 — 验证 InitiativeType 路由、多通道分发、异常隔离和被动队列逻辑。
 *
 * @author zsg
 * @since 2026-02-26
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock
    private NotificationChannel channelA;

    @Mock
    private NotificationChannel channelB;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private PassiveNotificationQueue passiveQueue;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        lenient().when(channelA.id()).thenReturn("channel-a");
        lenient().when(channelB.id()).thenReturn("channel-b");
        passiveQueue = new PassiveNotificationQueue();
        dispatcher = new NotificationDispatcher(List.of(channelA, channelB), passiveQueue, jdbcTemplate);
    }

    // ── NOTIFICATION 类型 — 多通道分发 ──────────────────────────

    @Test
    void NOTIFICATION_HIGH紧急度_分发到所有通道() {
        var notification = buildNotification(Urgency.HIGH);

        dispatcher.dispatch(notification, InitiativeType.NOTIFICATION);

        verify(channelA).send(notification);
        verify(channelB).send(notification);
    }

    @Test
    void NOTIFICATION_MEDIUM紧急度_分发到所有通道() {
        var notification = buildNotification(Urgency.MEDIUM);

        dispatcher.dispatch(notification, InitiativeType.NOTIFICATION);

        verify(channelA).send(notification);
        verify(channelB).send(notification);
    }

    // ── NOTIFICATION 类型 — 单通道异常不中断其余通道 ─────────────

    @Test
    void NOTIFICATION_通道A异常_通道B仍收到通知() {
        var notification = buildNotification(Urgency.HIGH);
        doThrow(new RuntimeException("通道A故障")).when(channelA).send(any());

        dispatcher.dispatch(notification, InitiativeType.NOTIFICATION);

        verify(channelA).send(notification);
        verify(channelB).send(notification);
    }

    @Test
    void NOTIFICATION_所有通道异常_不抛出异常() {
        var notification = buildNotification(Urgency.MEDIUM);
        doThrow(new RuntimeException("故障A")).when(channelA).send(any());
        doThrow(new RuntimeException("故障B")).when(channelB).send(any());

        dispatcher.dispatch(notification, InitiativeType.NOTIFICATION);

        verify(channelA).send(notification);
        verify(channelB).send(notification);
    }

    // ── NOTIFICATION 类型 — LOW 紧急度仅入队 ────────────────────

    @Test
    void NOTIFICATION_LOW紧急度_仅入队被动队列_不通过通道发送() {
        var notification = buildNotification(Urgency.LOW);

        dispatcher.dispatch(notification, InitiativeType.NOTIFICATION);

        verify(channelA, never()).send(any());
        verify(channelB, never()).send(any());
        assertThat(passiveQueue.drainAll()).containsExactly(notification);
    }

    @Test
    void NOTIFICATION_LOW紧急度_多次入队_全部保留() {
        var n1 = buildNotification(Urgency.LOW);
        var n2 = buildNotification(Urgency.LOW);

        dispatcher.dispatch(n1, InitiativeType.NOTIFICATION);
        dispatcher.dispatch(n2, InitiativeType.NOTIFICATION);

        assertThat(passiveQueue.drainAll()).containsExactly(n1, n2);
    }

    // ── PASSIVE_HINT 类型 — 直接入队被动队列 ────────────────────

    @Test
    void PASSIVE_HINT_HIGH紧急度_仍入队被动队列_不通过通道发送() {
        var notification = buildNotification(Urgency.HIGH);

        dispatcher.dispatch(notification, InitiativeType.PASSIVE_HINT);

        verify(channelA, never()).send(any());
        verify(channelB, never()).send(any());
        assertThat(passiveQueue.drainAll()).containsExactly(notification);
    }

    @Test
    void PASSIVE_HINT_LOW紧急度_入队被动队列() {
        var notification = buildNotification(Urgency.LOW);

        dispatcher.dispatch(notification, InitiativeType.PASSIVE_HINT);

        verify(channelA, never()).send(any());
        verify(channelB, never()).send(any());
        assertThat(passiveQueue.drainAll()).containsExactly(notification);
    }

    // ── 持久化 ──────────────────────────────────────────────────

    @Test
    void 所有紧急度_NOTIFICATION类型_均持久化到数据库() {
        for (Urgency urgency : Urgency.values()) {
            var notification = buildNotification(urgency);
            dispatcher.dispatch(notification, InitiativeType.NOTIFICATION);
        }

        // HIGH + MEDIUM + LOW = 3 次持久化
        verify(jdbcTemplate, times(3)).update(anyString(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void PASSIVE_HINT类型_也持久化到数据库() {
        var notification = buildNotification(Urgency.MEDIUM);

        dispatcher.dispatch(notification, InitiativeType.PASSIVE_HINT);

        verify(jdbcTemplate).update(anyString(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 持久化失败_不影响通知分发() {
        doThrow(new RuntimeException("DB 故障")).when(jdbcTemplate)
                .update(anyString(), any(), any(), any(), any(), any(), any(), any());
        var notification = buildNotification(Urgency.HIGH);

        dispatcher.dispatch(notification, InitiativeType.NOTIFICATION);

        verify(channelA).send(notification);
        verify(channelB).send(notification);
    }

    // ── 辅助方法 ────────────────────────────────────────────────

    private static ProactiveNotification buildNotification(Urgency urgency) {
        return new ProactiveNotification(
                UUID.randomUUID().toString(),
                "deadline_reminder",
                urgency,
                "测试通知内容",
                "test",
                "PENDING",
                Instant.now()
        );
    }
}
