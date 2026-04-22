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
    void file类型_pdf附件_content走TextMessage且hint含attachmentId() {
        setupMocks();
        String base64Data = Base64.getEncoder().encodeToString("文件内容".getBytes());
        var content = new ChannelRuntimeEventRequest.Content(
                "file", "这是一个文档", null,
                Map.of("fileName", "report.pdf", "mimeType", "application/pdf"));
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                "att-1", "report.pdf", "application/pdf", base64Data, 100);
        var request = buildEventRequest(content, List.of(attachment));

        ingressService.processEvent("feishu.test", request);

        GatewayMessage captured = captureGatewayMessage();
        // 根因修复：所有 file/image/audio/video 都统一走 TextMessage（caption + hint），
        // binary 旁挂到 GatewayMessage.attachments，下游已有的消费者直接能读
        assertThat(captured.content()).isInstanceOf(MessageContent.TextMessage.class);
        String text = ((MessageContent.TextMessage) captured.content()).text();
        assertThat(text).startsWith("这是一个文档");
        assertThat(text).contains("attachmentId=att-1");   // pdf 是文档类 MIME，hint 暴露 id
        // 元数据与 binary 在 attachments 字段
        assertThat(captured.attachments()).hasSize(1);
        assertThat(captured.attachments().getFirst().fileName()).isEqualTo("report.pdf");
        assertThat(captured.attachments().getFirst().mimeType()).isEqualTo("application/pdf");
        assertThat(captured.attachments().getFirst().data()).isEqualTo("文件内容".getBytes());
    }

    @Test
    void image类型_TextMessage用类型占位_attachments带binary() {
        setupMocks();
        String base64Data = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        var content = new ChannelRuntimeEventRequest.Content(
                "image", null, null, null);
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                "att-2", "photo.png", "image/png", base64Data, 4);
        var request = buildEventRequest(content, List.of(attachment));

        ingressService.processEvent("feishu.test", request);

        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.TextMessage.class);
        String text = ((MessageContent.TextMessage) captured.content()).text();
        // image 不在 DOCUMENT_MIME_PREFIXES 白名单，hint 不注入；caption 为空走类型占位
        assertThat(text).isEqualTo("[image]");
        assertThat(captured.attachments().getFirst().mimeType()).isEqualTo("image/png");
        assertThat(captured.attachments().getFirst().fileName()).isEqualTo("photo.png");
    }

    @Test
    void audio类型_TextMessage取caption原文() {
        setupMocks();
        String base64Data = Base64.getEncoder().encodeToString(new byte[]{10, 20, 30});
        var content = new ChannelRuntimeEventRequest.Content(
                "audio", "语音留言", null,
                Map.of("fileName", "voice.mp3", "mimeType", "audio/mpeg"));
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                "att-3", "voice.mp3", "audio/mpeg", base64Data, 3);
        var request = buildEventRequest(content, List.of(attachment));

        ingressService.processEvent("feishu.test", request);

        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.TextMessage.class);
        String text = ((MessageContent.TextMessage) captured.content()).text();
        // audio 非文档类，hint 不注入，原 caption 保留
        assertThat(text).isEqualTo("语音留言");
        assertThat(captured.attachments().getFirst().fileName()).isEqualTo("voice.mp3");
        assertThat(captured.attachments().getFirst().mimeType()).isEqualTo("audio/mpeg");
    }

    @Test
    void video类型_attachments保留元数据() {
        setupMocks();
        String base64Data = Base64.getEncoder().encodeToString(new byte[]{0x00, 0x01});
        var content = new ChannelRuntimeEventRequest.Content(
                "video", null, null,
                Map.of("fileName", "clip.mp4", "mimeType", "video/mp4"));
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                "att-4", "clip.mp4", "video/mp4", base64Data, 2);
        var request = buildEventRequest(content, List.of(attachment));

        ingressService.processEvent("feishu.test", request);

        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.TextMessage.class);
        assertThat(((MessageContent.TextMessage) captured.content()).text()).isEqualTo("[video]");
        assertThat(captured.attachments().getFirst().fileName()).isEqualTo("clip.mp4");
        assertThat(captured.attachments().getFirst().mimeType()).isEqualTo("video/mp4");
    }

    @Test
    void card_action类型_应构建EventMessage且eventType为card_action() {
        // given
        setupMocks();
        var payload = Map.<String, Object>of("action_tag", "button_approve", "value", "yes");
        var content = new ChannelRuntimeEventRequest.Content(
                "card-action", null, null, payload);
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
                "card-action", null, "custom_card_event",
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
    void file类型_多attachment_全部保留到GatewayMessage_attachments() {
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

        ingressService.processEvent("feishu.test", request);

        GatewayMessage captured = captureGatewayMessage();
        // content 是占位 TextMessage；binary 全部进 attachments 字段保持顺序
        assertThat(captured.content()).isInstanceOf(MessageContent.TextMessage.class);
        assertThat(captured.attachments()).hasSize(2);
        assertThat(captured.attachments().get(0).data()).isEqualTo(rawData);
        assertThat(captured.attachments().get(1).fileName()).isEqualTo("other.bin");
    }

    @Test
    void file类型_无attachments_content仍为TextMessage占位() {
        setupMocks();
        var content = new ChannelRuntimeEventRequest.Content(
                "file", null, null,
                Map.of("fileName", "remote-file.docx", "fileToken", "ft-abc123"));
        var request = buildEventRequest(content, List.of());

        ingressService.processEvent("feishu.test", request);

        GatewayMessage captured = captureGatewayMessage();
        // 无 attachments → 无 hint 可注入 → 走类型占位
        assertThat(captured.content()).isInstanceOf(MessageContent.TextMessage.class);
        assertThat(((MessageContent.TextMessage) captured.content()).text()).isEqualTo("[file]");
        assertThat(captured.attachments()).isEmpty();
    }

    @Test
    void file类型_无payload_无attachments_content占位() {
        setupMocks();
        var content = new ChannelRuntimeEventRequest.Content("file", null, null, null);
        var request = buildEventRequest(content, List.of());

        ingressService.processEvent("feishu.test", request);

        GatewayMessage captured = captureGatewayMessage();
        assertThat(captured.content()).isInstanceOf(MessageContent.TextMessage.class);
        assertThat(((MessageContent.TextMessage) captured.content()).text()).isEqualTo("[file]");
        assertThat(captured.attachments()).isEmpty();
    }

    @Test
    void image_audio_video类型_无payload_无attachments_content按类型占位() {
        setupMocks();

        ingressService.processEvent("feishu.test", buildEventRequest(
                new ChannelRuntimeEventRequest.Content("image", null, null, null), List.of()));
        assertThat(((MessageContent.TextMessage) captureGatewayMessage().content()).text())
                .isEqualTo("[image]");
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
