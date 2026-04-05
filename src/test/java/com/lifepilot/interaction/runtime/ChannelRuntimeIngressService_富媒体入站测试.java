package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventResponse;
import com.lifepilot.interaction.service.ChannelIngressService;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import com.lifepilot.interaction.service.ChannelInstanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelRuntimeIngressService} 富媒体入站测试。
 *
 * <p>针对 file / image / audio / video / card_action 等内容类型的构建逻辑进行验证。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
@ExtendWith(MockitoExtension.class)
class ChannelRuntimeIngressService_富媒体入站测试 {

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

    private ChannelRuntimeIngressService ingressService;

    private ChannelInstance activeInstance;

    @BeforeEach
    void 初始化() {
        ingressService = new ChannelRuntimeIngressService(
                channelInstanceService,
                channelIngressService,
                connectorRuntimeManager,
                channelInstanceEventService,
                channelDeliveryDispatcher,
                10L * 1024 * 1024
        );

        activeInstance = new ChannelInstance(
                "feishu.test",
                "feishu",
                "feishu",
                "飞书测试",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of(),
                Map.of(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY, "token-123"),
                null, null, null,
                Instant.parse("2026-04-02T10:00:00Z"),
                Instant.parse("2026-04-02T10:00:00Z")
        );
    }

    @Test
    void file类型_应构建FileMessage并包含fileName和mimeType() {
        // given
        setupMocks();
        String base64Data = Base64.getEncoder().encodeToString("文件内容".getBytes());
        var content = new ChannelRuntimeEventRequest.Content(
                "file", "这是一个文档", null,
                Map.of("fileName", "report.pdf", "mimeType", "application/pdf"));
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                "att-1", "report.pdf", "application/pdf", base64Data, 100);
        var request = buildEventRequest(content, List.of(attachment));

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.FileMessage.class);
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        assertThat(fileMsg.fileName()).isEqualTo("report.pdf");
        assertThat(fileMsg.mimeType()).isEqualTo("application/pdf");
        assertThat(fileMsg.caption()).isEqualTo("这是一个文档");
        assertThat(fileMsg.data()).isEqualTo("文件内容".getBytes());
    }

    @Test
    void image类型_应构建FileMessage并使用默认mimeType() {
        // given
        setupMocks();
        String base64Data = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        var content = new ChannelRuntimeEventRequest.Content(
                "image", null, null, null);
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                "att-2", "photo.png", "image/png", base64Data, 4);
        var request = buildEventRequest(content, List.of(attachment));

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.FileMessage.class);
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        // payload 无 mimeType 时使用默认值 image/png
        assertThat(fileMsg.mimeType()).isEqualTo("image/png");
        assertThat(fileMsg.fileName()).isEqualTo("image.png");
        assertThat(fileMsg.caption()).isNull();
    }

    @Test
    void audio类型_应构建FileMessage() {
        // given
        setupMocks();
        String base64Data = Base64.getEncoder().encodeToString(new byte[]{10, 20, 30});
        var content = new ChannelRuntimeEventRequest.Content(
                "audio", "语音留言", null,
                Map.of("fileName", "voice.mp3", "mimeType", "audio/mpeg"));
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                "att-3", "voice.mp3", "audio/mpeg", base64Data, 3);
        var request = buildEventRequest(content, List.of(attachment));

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.FileMessage.class);
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        assertThat(fileMsg.fileName()).isEqualTo("voice.mp3");
        assertThat(fileMsg.mimeType()).isEqualTo("audio/mpeg");
        assertThat(fileMsg.caption()).isEqualTo("语音留言");
    }

    @Test
    void video类型_应构建FileMessage() {
        // given
        setupMocks();
        String base64Data = Base64.getEncoder().encodeToString(new byte[]{0x00, 0x01});
        var content = new ChannelRuntimeEventRequest.Content(
                "video", null, null,
                Map.of("fileName", "clip.mp4", "mimeType", "video/mp4"));
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                "att-4", "clip.mp4", "video/mp4", base64Data, 2);
        var request = buildEventRequest(content, List.of(attachment));

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.FileMessage.class);
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        assertThat(fileMsg.fileName()).isEqualTo("clip.mp4");
        assertThat(fileMsg.mimeType()).isEqualTo("video/mp4");
    }

    @Test
    void card_action类型_应构建EventMessage且eventType为card_action() {
        // given
        setupMocks();
        var payload = Map.<String, Object>of("action_tag", "button_approve", "value", "yes");
        var content = new ChannelRuntimeEventRequest.Content(
                "card_action", null, null, payload);
        var request = buildEventRequest(content, List.of());

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.EventMessage.class);
        MessageContent.EventMessage eventMsg = (MessageContent.EventMessage) captured.content();
        assertThat(eventMsg.eventType()).isEqualTo("card_action");
        assertThat(eventMsg.payload()).containsEntry("action_tag", "button_approve");
        assertThat(eventMsg.payload()).containsEntry("value", "yes");
    }

    @Test
    void card_action类型_指定了name_应使用指定的eventType() {
        // given
        setupMocks();
        var content = new ChannelRuntimeEventRequest.Content(
                "card_action", null, "custom_card_event",
                Map.of("key", "val"));
        var request = buildEventRequest(content, List.of());

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.EventMessage.class);
        MessageContent.EventMessage eventMsg = (MessageContent.EventMessage) captured.content();
        assertThat(eventMsg.eventType()).isEqualTo("custom_card_event");
    }

    @Test
    void file类型_有attachments_应从第一个attachment解码数据() {
        // given
        setupMocks();
        byte[] rawData = "附件原始数据".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(rawData);
        var content = new ChannelRuntimeEventRequest.Content(
                "file", null, null,
                Map.of("fileName", "data.bin"));
        var attachment1 = new ChannelRuntimeEventRequest.Attachment(
                "att-first", "data.bin", "application/octet-stream", base64Data, rawData.length);
        var attachment2 = new ChannelRuntimeEventRequest.Attachment(
                "att-second", "other.bin", "application/octet-stream",
                Base64.getEncoder().encodeToString("其他数据".getBytes()), 10);
        var request = buildEventRequest(content, List.of(attachment1, attachment2));

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        assertThat(fileMsg.data()).isEqualTo(rawData);
    }

    @Test
    void file类型_无attachments但有fileToken_data应为空() {
        // given
        setupMocks();
        var content = new ChannelRuntimeEventRequest.Content(
                "file", null, null,
                Map.of("fileName", "remote-file.docx", "fileToken", "ft-abc123"));
        var request = buildEventRequest(content, List.of());

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        assertThat(fileMsg.data()).isEmpty();
        assertThat(fileMsg.fileName()).isEqualTo("remote-file.docx");
    }

    @Test
    void file类型_无payload_应使用默认fileName和mimeType() {
        // given
        setupMocks();
        var content = new ChannelRuntimeEventRequest.Content("file", null, null, null);
        var request = buildEventRequest(content, List.of());

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        assertThat(fileMsg.fileName()).isEqualTo("file.bin");
        assertThat(fileMsg.mimeType()).isEqualTo("application/octet-stream");
    }

    @Test
    void image类型_无payload_应使用image默认值() {
        // given
        setupMocks();
        var content = new ChannelRuntimeEventRequest.Content("image", null, null, null);
        var request = buildEventRequest(content, List.of());

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        assertThat(fileMsg.fileName()).isEqualTo("image.png");
        assertThat(fileMsg.mimeType()).isEqualTo("image/png");
    }

    @Test
    void audio类型_无payload_应使用audio默认值() {
        // given
        setupMocks();
        var content = new ChannelRuntimeEventRequest.Content("audio", null, null, null);
        var request = buildEventRequest(content, List.of());

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        assertThat(fileMsg.fileName()).isEqualTo("audio.mp3");
        assertThat(fileMsg.mimeType()).isEqualTo("audio/mpeg");
    }

    @Test
    void video类型_无payload_应使用video默认值() {
        // given
        setupMocks();
        var content = new ChannelRuntimeEventRequest.Content("video", null, null, null);
        var request = buildEventRequest(content, List.of());

        // when
        ingressService.processEvent("feishu.test", request);

        // then
        GatewayMessage captured = captureGatewayMessage();
        MessageContent.FileMessage fileMsg = (MessageContent.FileMessage) captured.content();
        assertThat(fileMsg.fileName()).isEqualTo("video.mp4");
        assertThat(fileMsg.mimeType()).isEqualTo("video/mp4");
    }

    // ─── 辅助方法 ───────────────────────────────────

    private void setupMocks() {
        when(channelInstanceService.find("feishu.test")).thenReturn(Optional.of(activeInstance));

        var gatewayResponse = GatewayResponse.success(
                ChannelType.FEISHU, new ResponseContent.TextContent("已收到"));
        when(channelIngressService.submitSync(any(GatewayMessage.class))).thenReturn(gatewayResponse);
        when(connectorRuntimeManager.markHeartbeat("feishu.test")).thenReturn(activeInstance);

        var eventResponse = new ChannelRuntimeEventResponse(
                true, "resp-1", 200, null, List.of());
        when(channelDeliveryDispatcher.buildEventResponse(any(), any(), any())).thenReturn(eventResponse);
    }

    private ChannelRuntimeEventRequest buildEventRequest(ChannelRuntimeEventRequest.Content content,
                                                          List<ChannelRuntimeEventRequest.Attachment> attachments) {
        return new ChannelRuntimeEventRequest(
                "evt-001",
                "msg-001",
                "user-001",
                "session-001",
                content,
                attachments,
                null,
                null,
                null,
                Instant.now()
        );
    }

    private GatewayMessage captureGatewayMessage() {
        ArgumentCaptor<GatewayMessage> captor = ArgumentCaptor.forClass(GatewayMessage.class);
        verify(channelIngressService).submitSync(captor.capture());
        return captor.getValue();
    }
}
