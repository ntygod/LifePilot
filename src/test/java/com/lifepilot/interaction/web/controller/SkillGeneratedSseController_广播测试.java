package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.config.WebProperties;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.skill.event.SkillGeneratedEvent;
import com.lifepilot.skill.install.SkillSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SkillGeneratedSseController} SSE 广播测试 — 覆盖：
 *
 * <ul>
 *   <li>GET /skills/events 委托 {@link SseSessionManager#createNotificationEmitter}
 *       使用 {@code skill-events-} 前缀和配置中的超时值</li>
 *   <li>监听 {@link SkillGeneratedEvent} 后按前缀广播扁平 payload（type/skillName/sourceType/at）</li>
 *   <li>多次事件触发多次广播 —— 每次独立走一次 {@link SseSessionManager#broadcastByPrefix}</li>
 *   <li>无事件时不触发任何广播调用</li>
 * </ul>
 *
 * <p>由于 {@link SseSessionManager#createNotificationEmitter} 的 emitter 生命周期、
 * 断开清理、多订阅者 fan-out 等已在 manager 自身的集成里覆盖（见
 * {@link com.lifepilot.interaction.web.controller.NotificationSseController} 调用链），
 * 本类聚焦 Controller 层的"监听器→广播"契约。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class SkillGeneratedSseController_广播测试 {

    @Mock
    private SseSessionManager sseSessionManager;

    @Mock
    private WebProperties webProperties;

    @Mock
    private WebProperties.SseProperties sseProperties;

    private SkillGeneratedSseController controller;

    @BeforeEach
    void 初始化() {
        controller = new SkillGeneratedSseController(sseSessionManager, webProperties);
    }

    @Test
    void GET_events应创建skill_events前缀的emitter并使用配置超时() {
        // given
        long expectedTimeout = 1_800_000L;
        when(webProperties.sse()).thenReturn(sseProperties);
        when(sseProperties.mcpStatusTimeout()).thenReturn(expectedTimeout);
        SseEmitter stub = new SseEmitter(expectedTimeout);
        when(sseSessionManager.createNotificationEmitter(anyString(), anyLong())).thenReturn(stub);

        // when
        SseEmitter result = controller.events();

        // then — 断言 emitter 本体返回 + streamId 前缀 + timeout 均来自配置
        assertThat(result).isSameAs(stub);
        ArgumentCaptor<String> streamIdCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> timeoutCap = ArgumentCaptor.forClass(Long.class);
        verify(sseSessionManager).createNotificationEmitter(streamIdCap.capture(), timeoutCap.capture());
        assertThat(streamIdCap.getValue()).startsWith(SkillGeneratedSseController.STREAM_ID_PREFIX);
        assertThat(timeoutCap.getValue()).isEqualTo(expectedTimeout);
    }

    @Test
    void 监听Generated事件应按前缀广播扁平payload() {
        // given
        Instant at = Instant.parse("2026-04-24T12:34:56Z");
        var event = new SkillGeneratedEvent("todo-helper", SkillSourceType.AUTO_GENERATED, at);

        // when
        controller.onSkillGenerated(event);

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCap = ArgumentCaptor.forClass(Map.class);
        verify(sseSessionManager).broadcastByPrefix(
                eq(SkillGeneratedSseController.STREAM_ID_PREFIX),
                eq(SseEventType.SKILL_GENERATED),
                payloadCap.capture());
        Map<String, Object> payload = payloadCap.getValue();
        assertThat(payload).containsEntry("type", SseEventType.SKILL_GENERATED);
        assertThat(payload).containsEntry("skillName", "todo-helper");
        assertThat(payload).containsEntry("sourceType", SkillSourceType.AUTO_GENERATED.name());
        assertThat(payload).containsEntry("at", "2026-04-24T12:34:56Z");
    }

    @Test
    void 多次发布事件应分别广播() {
        // given — 模拟两个相继完成的自生成
        var events = List.of(
                new SkillGeneratedEvent("s1", SkillSourceType.AUTO_GENERATED, Instant.parse("2026-04-24T00:00:00Z")),
                new SkillGeneratedEvent("s2", SkillSourceType.AUTO_GENERATED, Instant.parse("2026-04-24T00:00:01Z")));

        // when
        events.forEach(controller::onSkillGenerated);

        // then — 广播被触发两次，每次包含自己的 skillName
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCap = ArgumentCaptor.forClass(Map.class);
        verify(sseSessionManager, org.mockito.Mockito.times(2)).broadcastByPrefix(
                eq(SkillGeneratedSseController.STREAM_ID_PREFIX),
                eq(SseEventType.SKILL_GENERATED),
                payloadCap.capture());
        List<Map<String, Object>> payloads = payloadCap.getAllValues();
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0)).containsEntry("skillName", "s1");
        assertThat(payloads.get(1)).containsEntry("skillName", "s2");
    }

    @Test
    void 未触发事件时不应调用广播() {
        // 仅构造 controller，不触发任何事件 —— 断言 SseSessionManager 完全空闲
        verifyNoInteractions(sseSessionManager);
    }
}
