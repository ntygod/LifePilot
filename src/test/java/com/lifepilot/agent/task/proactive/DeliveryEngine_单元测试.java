package com.lifepilot.agent.task.proactive;

import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DeliveryEngine 单元测试。
 *
 * @author zsg
 * @since 2026-04-14
 */
class DeliveryEngine_单元测试 {

    NotificationService notificationService;
    QueuedActionRepository queuedActionRepository;
    DeliveryEngine engine;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        queuedActionRepository = mock(QueuedActionRepository.class);
        engine = new DeliveryEngine(notificationService, queuedActionRepository);
    }

    @Test
    void SILENT级别仅记录不投递() {
        var action = testAction(0.2f);

        var result = engine.deliver(action, DeliveryLevel.SILENT, "u1");

        assertThat(result.level()).isEqualTo(DeliveryLevel.SILENT);
        assertThat(result.notificationId()).isNull();
        verifyNoInteractions(notificationService);
        verifyNoInteractions(queuedActionRepository);
    }

    @Test
    void QUEUE级别存入排队表() {
        var action = testAction(0.4f);

        var result = engine.deliver(action, DeliveryLevel.QUEUE, "u1");

        assertThat(result.level()).isEqualTo(DeliveryLevel.QUEUE);
        assertThat(result.notificationId()).isNull();
        verify(queuedActionRepository).save(any(QueuedActionRecord.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void QUEUE保存的记录字段正确() {
        var action = testAction(0.45f);

        engine.deliver(action, DeliveryLevel.QUEUE, "u1");

        var captor = ArgumentCaptor.forClass(QueuedActionRecord.class);
        verify(queuedActionRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.userId()).isEqualTo("u1");
        assertThat(saved.behavior()).isEqualTo("reminder");
        assertThat(saved.topicKey()).isEqualTo("topic-1");
        assertThat(saved.content()).isEqualTo("测试内容");
        assertThat(saved.shown()).isFalse();
    }

    @Test
    void NOTIFY级别发送通知() {
        when(notificationService.send(any())).thenReturn(List.of("nid-1"));
        var action = testAction(0.6f);

        var result = engine.deliver(action, DeliveryLevel.NOTIFY, "u1");

        assertThat(result.level()).isEqualTo(DeliveryLevel.NOTIFY);
        assertThat(result.notificationId()).isEqualTo("nid-1");
        verify(notificationService).send(any(NotificationRequest.class));
    }

    @Test
    void INTERRUPT级别发送通知带打断标记() {
        when(notificationService.send(any())).thenReturn(List.of("nid-2"));
        var action = testAction(0.8f);

        var result = engine.deliver(action, DeliveryLevel.INTERRUPT, "u1");

        assertThat(result.level()).isEqualTo(DeliveryLevel.INTERRUPT);
        assertThat(result.notificationId()).isEqualTo("nid-2");
        var captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().metadata()).containsEntry("deliveryLevel", "INTERRUPT");
    }

    @Test
    void NOTIFY通知内容包含标题和消息() {
        when(notificationService.send(any())).thenReturn(List.of("nid-3"));
        var action = testAction(0.6f);

        engine.deliver(action, DeliveryLevel.NOTIFY, "u1");

        var captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        assertThat(captor.getValue().content().toPlainText()).contains("测试内容");
    }

    private ProactiveAction testAction(float score) {
        var candidate = new ProactiveCandidate(
                "c1", "reminder", "topic-1", "标题", score, "理由", null);
        return new ProactiveAction(candidate, "测试内容", DeliveryLevel.fromScore(score), null);
    }
}
