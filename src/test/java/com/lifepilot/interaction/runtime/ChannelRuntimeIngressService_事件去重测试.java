package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventResponse;
import com.lifepilot.interaction.service.ChannelIngressService;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import com.lifepilot.interaction.service.ChannelInstanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelRuntimeIngressService} 事件去重测试。
 *
 * <p>验证基于 eventId 的 FIFO LRU 缓存去重逻辑：重复提交、不同 eventId、null/空白 eventId、缓存淘汰。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class ChannelRuntimeIngressService_事件去重测试 {

    @Mock
    private ChannelInstanceService channelInstanceService;

    @Mock
    private ChannelIngressService channelIngressService;

    @Mock
    private ConnectorRuntimeManager connectorRuntimeManager;

    @Mock
    private ChannelInstanceEventService channelInstanceEventService;

    @Mock
    private ChannelDeliveryDispatcher channelDeliveryDispatcher;

    private static final String INSTANCE_ID = "feishu.test";

    private ChannelInstance activeInstance;

    // ─── 辅助方法 ───────────────────────────────────

    /**
     * 构造指定缓存容量的 IngressService 实例。
     */
    private ChannelRuntimeIngressService 创建服务(int eventCacheMaxSize) {
        return new ChannelRuntimeIngressService(
                channelInstanceService,
                channelIngressService,
                connectorRuntimeManager,
                channelInstanceEventService,
                channelDeliveryDispatcher,
                null,
                null,
                10L * 1024 * 1024,
                eventCacheMaxSize
        );
    }

    /**
     * 构造默认缓存容量的 IngressService 实例。
     */
    private ChannelRuntimeIngressService 创建服务() {
        return new ChannelRuntimeIngressService(
                channelInstanceService,
                channelIngressService,
                connectorRuntimeManager,
                channelInstanceEventService,
                channelDeliveryDispatcher,
                10L * 1024 * 1024
        );
    }

    private ChannelRuntimeEventRequest 构造文本请求(String eventId) {
        var content = new ChannelRuntimeEventRequest.Content("text", "你好", null, null);
        return new ChannelRuntimeEventRequest(
                eventId, "msg-001", "user-001", "session-001",
                content, List.of(), null, null, null, Instant.now()
        );
    }

    private void 配置正常处理Mock() {
        when(channelInstanceService.find(INSTANCE_ID)).thenReturn(Optional.of(activeInstance));

        var gatewayResponse = GatewayResponse.success(
                ChannelType.FEISHU, new ResponseContent.TextContent("已收到"));
        when(channelIngressService.submitSync(any(GatewayMessage.class))).thenReturn(gatewayResponse);
        when(connectorRuntimeManager.markHeartbeat(INSTANCE_ID)).thenReturn(activeInstance);

        var eventResponse = new ChannelRuntimeEventResponse(true, "resp-1", 200, null, List.of());
        when(channelDeliveryDispatcher.buildEventResponse(any(), any(), any())).thenReturn(eventResponse);
    }

    @BeforeEach
    void 初始化() {
        activeInstance = new ChannelInstance(
                INSTANCE_ID, "feishu", "feishu", "飞书测试",
                true, ChannelInstanceStatus.RUNNING,
                Map.of(),
                Map.of(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY, "token-123"),
                null, null, null,
                Instant.parse("2026-04-02T10:00:00Z"),
                Instant.parse("2026-04-02T10:00:00Z")
        );
    }

    // ─── 场景 1：相同 eventId 重复提交 ───────────────────

    @Test
    void 相同eventId重复提交_第二次应返回duplicate响应且不调用submitSync() {
        // given
        var service = 创建服务();
        配置正常处理Mock();
        var request = 构造文本请求("evt-dup-001");

        // when — 第一次提交，正常处理
        ChannelRuntimeEventResponse first = service.processEvent(INSTANCE_ID, request);

        // then — 第一次正常返回
        assertThat(first.accepted()).isTrue();
        assertThat(first.responseId()).isEqualTo("resp-1");
        verify(channelIngressService, times(1)).submitSync(any(GatewayMessage.class));

        // when — 第二次提交相同 eventId
        ChannelRuntimeEventResponse second = service.processEvent(INSTANCE_ID, request);

        // then — 返回 duplicate，且 submitSync 仍然只调用了一次
        assertThat(second.accepted()).isTrue();
        assertThat(second.responseId()).isNull();
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.errorMessage()).isEqualTo("重复事件已忽略: evt-dup-001");
        assertThat(second.deliveries()).isEmpty();
        verify(channelIngressService, times(1)).submitSync(any(GatewayMessage.class));
    }

    @Test
    void 相同eventId第三次提交_仍返回duplicate() {
        // given
        var service = 创建服务();
        配置正常处理Mock();
        var request = 构造文本请求("evt-triple");

        // when
        service.processEvent(INSTANCE_ID, request);
        service.processEvent(INSTANCE_ID, request);
        ChannelRuntimeEventResponse third = service.processEvent(INSTANCE_ID, request);

        // then — 只有第一次实际处理
        assertThat(third.errorMessage()).isEqualTo("重复事件已忽略: evt-triple");
        verify(channelIngressService, times(1)).submitSync(any(GatewayMessage.class));
    }

    // ─── 场景 2：不同 eventId 各自正常处理 ─────────────────

    @Test
    void 不同eventId_应各自正常处理() {
        // given
        var service = 创建服务();
        配置正常处理Mock();
        var requestA = 构造文本请求("evt-A");
        var requestB = 构造文本请求("evt-B");

        // when
        ChannelRuntimeEventResponse responseA = service.processEvent(INSTANCE_ID, requestA);
        ChannelRuntimeEventResponse responseB = service.processEvent(INSTANCE_ID, requestB);

        // then — 两次均正常返回，submitSync 被调用两次
        assertThat(responseA.accepted()).isTrue();
        assertThat(responseA.responseId()).isEqualTo("resp-1");
        assertThat(responseB.accepted()).isTrue();
        assertThat(responseB.responseId()).isEqualTo("resp-1");
        verify(channelIngressService, times(2)).submitSync(any(GatewayMessage.class));
    }

    // ─── 场景 3：eventId 为 null 不做去重 ─────────────────

    @Test
    void eventId为null_每次都正常处理不去重() {
        // given
        var service = 创建服务();
        配置正常处理Mock();
        var request1 = 构造文本请求(null);
        var request2 = 构造文本请求(null);

        // when
        ChannelRuntimeEventResponse response1 = service.processEvent(INSTANCE_ID, request1);
        ChannelRuntimeEventResponse response2 = service.processEvent(INSTANCE_ID, request2);

        // then — 两次均正常处理
        assertThat(response1.accepted()).isTrue();
        assertThat(response1.responseId()).isEqualTo("resp-1");
        assertThat(response2.accepted()).isTrue();
        assertThat(response2.responseId()).isEqualTo("resp-1");
        verify(channelIngressService, times(2)).submitSync(any(GatewayMessage.class));
    }

    // ─── 场景 4：eventId 为空白字符串不做去重 ───────────────

    @Test
    void eventId为空字符串_每次都正常处理不去重() {
        // given
        var service = 创建服务();
        配置正常处理Mock();
        var request1 = 构造文本请求("");
        var request2 = 构造文本请求("");

        // when
        ChannelRuntimeEventResponse response1 = service.processEvent(INSTANCE_ID, request1);
        ChannelRuntimeEventResponse response2 = service.processEvent(INSTANCE_ID, request2);

        // then
        assertThat(response1.responseId()).isEqualTo("resp-1");
        assertThat(response2.responseId()).isEqualTo("resp-1");
        verify(channelIngressService, times(2)).submitSync(any(GatewayMessage.class));
    }

    @Test
    void eventId为空白字符_每次都正常处理不去重() {
        // given
        var service = 创建服务();
        配置正常处理Mock();
        var request1 = 构造文本请求("   ");
        var request2 = 构造文本请求("\t\n");

        // when
        ChannelRuntimeEventResponse response1 = service.processEvent(INSTANCE_ID, request1);
        ChannelRuntimeEventResponse response2 = service.processEvent(INSTANCE_ID, request2);

        // then
        assertThat(response1.responseId()).isEqualTo("resp-1");
        assertThat(response2.responseId()).isEqualTo("resp-1");
        verify(channelIngressService, times(2)).submitSync(any(GatewayMessage.class));
    }

    // ─── 场景 5：缓存超过容量后最早的 eventId 被淘汰 ──────────

    @Test
    void 缓存容量为3_第4个事件应淘汰最早的eventId使其可重新处理() {
        // given — 缓存容量设为 3
        var service = 创建服务(3);
        配置正常处理Mock();

        // when — 依次提交 evt-1, evt-2, evt-3, evt-4
        service.processEvent(INSTANCE_ID, 构造文本请求("evt-1"));
        service.processEvent(INSTANCE_ID, 构造文本请求("evt-2"));
        service.processEvent(INSTANCE_ID, 构造文本请求("evt-3"));
        // 此时缓存已满 [evt-1, evt-2, evt-3]，提交 evt-4 将淘汰 evt-1
        service.processEvent(INSTANCE_ID, 构造文本请求("evt-4"));

        // then — evt-1 已被淘汰，重新提交应正常处理
        ChannelRuntimeEventResponse resubmitResponse = service.processEvent(INSTANCE_ID, 构造文本请求("evt-1"));
        assertThat(resubmitResponse.accepted()).isTrue();
        assertThat(resubmitResponse.responseId()).isEqualTo("resp-1");
        // 总共 5 次正常处理：evt-1, evt-2, evt-3, evt-4, 再次 evt-1
        verify(channelIngressService, times(5)).submitSync(any(GatewayMessage.class));
    }

    @Test
    void 缓存容量为3_未被淘汰的eventId仍然被去重() {
        // given — 缓存容量设为 3
        var service = 创建服务(3);
        配置正常处理Mock();

        // when — 依次提交 evt-1, evt-2, evt-3，然后重复提交 evt-3
        service.processEvent(INSTANCE_ID, 构造文本请求("evt-1"));
        service.processEvent(INSTANCE_ID, 构造文本请求("evt-2"));
        service.processEvent(INSTANCE_ID, 构造文本请求("evt-3"));
        ChannelRuntimeEventResponse dupResponse = service.processEvent(INSTANCE_ID, 构造文本请求("evt-3"));

        // then — evt-3 仍在缓存中，返回 duplicate
        assertThat(dupResponse.errorMessage()).isEqualTo("重复事件已忽略: evt-3");
        verify(channelIngressService, times(3)).submitSync(any(GatewayMessage.class));
    }

    @Test
    void 缓存容量为1_每次新事件淘汰前一个() {
        // given — 极端情况：缓存容量仅为 1
        var service = 创建服务(1);
        配置正常处理Mock();

        // when
        service.processEvent(INSTANCE_ID, 构造文本请求("evt-only"));
        // 重复提交 evt-only，仍在缓存中，返回 duplicate
        ChannelRuntimeEventResponse dupResponse = service.processEvent(INSTANCE_ID, 构造文本请求("evt-only"));
        assertThat(dupResponse.errorMessage()).isEqualTo("重复事件已忽略: evt-only");

        // 提交 evt-new，淘汰 evt-only
        service.processEvent(INSTANCE_ID, 构造文本请求("evt-new"));

        // evt-only 已被淘汰，可重新处理
        ChannelRuntimeEventResponse resubmit = service.processEvent(INSTANCE_ID, 构造文本请求("evt-only"));
        assertThat(resubmit.accepted()).isTrue();
        assertThat(resubmit.responseId()).isEqualTo("resp-1");
        // 共 3 次正常处理：evt-only(首次), evt-new, evt-only(重新)
        verify(channelIngressService, times(3)).submitSync(any(GatewayMessage.class));
    }

    // ─── 补充场景：去重不影响后续正常流程 ────────────────────

    @Test
    void 重复事件不调用heartbeat和事件记录() {
        // given
        var service = 创建服务();
        配置正常处理Mock();
        var request = 构造文本请求("evt-no-side-effect");

        // when — 第一次正常处理
        service.processEvent(INSTANCE_ID, request);
        // 第二次重复
        service.processEvent(INSTANCE_ID, request);

        // then — heartbeat 和事件记录只调用一次（第一次正常处理时）
        verify(connectorRuntimeManager, times(1)).markHeartbeat(INSTANCE_ID);
        verify(channelInstanceEventService, times(1))
                .record(any(), any(), any(), any());
        verify(channelDeliveryDispatcher, times(1))
                .buildEventResponse(any(), any(), any());
    }

    @Test
    void 重复事件不调用requireActiveInstance校验() {
        // given
        var service = 创建服务();
        配置正常处理Mock();
        var request = 构造文本请求("evt-skip-validation");

        // when
        service.processEvent(INSTANCE_ID, request);
        service.processEvent(INSTANCE_ID, request);

        // then — channelInstanceService.find 只被调用一次（第一次正常处理时）
        verify(channelInstanceService, times(1)).find(INSTANCE_ID);
    }
}
