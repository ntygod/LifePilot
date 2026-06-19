package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProactiveEngine 单元测试。
 *
 * @author zsg
 * @since 2026-04-14
 */
class ProactiveEngine_单元测试 {

    ProactiveBehavior behavior;
    DecisionGate gate;
    DeliveryEngine delivery;
    ProactiveEngine engine;

    @BeforeEach
    void setUp() {
        behavior = mock(ProactiveBehavior.class);
        when(behavior.name()).thenReturn("test-behavior");
        gate = mock(DecisionGate.class);
        delivery = mock(DeliveryEngine.class);
        engine = new ProactiveEngine(List.of(behavior), gate, delivery,
                null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void Gate1_无变化时SILENT不调detect() {
        // focusState idle 超过心跳间隔 → hasChangeSinceLastHeartbeat=false
        var focus = new ReminderFocusState("explorer.exe", "Desktop", false, 60, Instant.now());
        var ctx = new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, focus,
                Instant.now().minusSeconds(1800), 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.SILENT);
        verifyNoInteractions(behavior);
        verifyNoInteractions(gate);
        verifyNoInteractions(delivery);
    }

    @Test
    void Gate1_首次运行无lastHeartbeat时通过() {
        var ctx = activeCtx();
        when(behavior.detect(any())).thenReturn(List.of());

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.FAST);
        verify(behavior).detect(any());
    }

    @Test
    void Gate2_无候选时FAST不调reason() {
        var ctx = activeCtx();
        when(behavior.detect(any())).thenReturn(List.of());

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.FAST);
        verify(behavior).detect(any());
        verify(behavior, never()).reason(anyList(), any());
    }

    @Test
    void Gate2_所有候选低分时FAST() {
        var ctx = activeCtx();
        var lowCandidate = new ProactiveCandidate(
                "c1", "test-behavior", "topic", "Title", 0.3f, "", null);
        when(behavior.detect(any())).thenReturn(List.of(lowCandidate));

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.FAST);
        verify(behavior, never()).reason(anyList(), any());
    }

    @Test
    void Gate3_高分候选走完整流程FULL() {
        var ctx = activeCtx();
        var candidate = new ProactiveCandidate(
                "c1", "test-behavior", "topic", "Title", 0.75f, "", null);
        when(behavior.detect(any())).thenReturn(List.of(candidate));

        var action = new ProactiveAction(candidate, "内容", DeliveryLevel.INTERRUPT, null);
        when(behavior.reason(anyList(), any())).thenReturn(List.of(action));

        var gatedAction = new DecisionGate.GatedAction(action, DeliveryLevel.INTERRUPT);
        when(gate.evaluate(anyList(), any())).thenReturn(List.of(gatedAction));

        var deliveryResult = new DeliveryResult("nid-1", DeliveryLevel.INTERRUPT, Instant.now());
        when(delivery.deliver(any(), any(), anyString())).thenReturn(deliveryResult);

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.FULL);
        verify(behavior).detect(any());
        verify(behavior).reason(anyList(), any());
        verify(gate).evaluate(anyList(), any());
        verify(delivery).deliver(eq(action), eq(DeliveryLevel.INTERRUPT), eq("u1"));
        verify(behavior).onDelivered(eq(action), eq(deliveryResult));
    }

    @Test
    void 多个候选仅高分进入reason() {
        var ctx = activeCtx();
        var low = new ProactiveCandidate("c1", "test-behavior", "low", "Low", 0.2f, "", null);
        var high = new ProactiveCandidate("c2", "test-behavior", "high", "High", 0.8f, "", null);
        when(behavior.detect(any())).thenReturn(List.of(low, high));

        var action = new ProactiveAction(high, "内容", DeliveryLevel.INTERRUPT, null);
        when(behavior.reason(anyList(), any())).thenReturn(List.of(action));
        when(gate.evaluate(anyList(), any())).thenReturn(List.of());

        engine.heartbeat(ctx);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(behavior).reason(captor.capture(), any());
        @SuppressWarnings("unchecked")
        List<ProactiveCandidate> reasoned = captor.getValue();
        assertThat(reasoned).hasSize(1);
        assertThat(reasoned.getFirst().topicKey()).isEqualTo("high");
    }

    @Test
    void 插件detect异常不中断引擎() {
        var ctx = activeCtx();
        when(behavior.detect(any())).thenThrow(new RuntimeException("模拟异常"));

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.FAST);
    }

    @Test
    void 插件reason异常不中断引擎() {
        var ctx = activeCtx();
        var candidate = new ProactiveCandidate(
                "c1", "test-behavior", "topic", "Title", 0.8f, "", null);
        when(behavior.detect(any())).thenReturn(List.of(candidate));
        when(behavior.reason(anyList(), any())).thenThrow(new RuntimeException("模拟异常"));

        var result = engine.heartbeat(ctx);

        assertThat(result).isEqualTo(DetectionLevel.FULL);
    }

    // ── helpers ──

    private ContextPacket activeCtx() {
        return new ContextPacket("u1", Instant.now(), ZoneId.of("Asia/Shanghai"),
                null, null, 0, 5, null, null, 30, null, null,
                BoundaryState.UNKNOWN, FocusMode.NORMAL);
    }
}
