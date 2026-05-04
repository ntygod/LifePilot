package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.attachment.DocumentAttachmentHintBuilder;
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
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChannelRuntimeIngressService 的文档附件持久化 + hint 注入测试（P0-1）。
 *
 * <p>验证 channel 路径（飞书/钉钉/企微）收到 text + docx 附件时：</p>
 * <ul>
 *   <li>附件会落盘到 attachmentStorageDir 并 saveForEntry(entryId=null) 拿到 attachmentId</li>
 *   <li>GatewayMessage.content 的 TextMessage 文本里注入 document hint</li>
 *   <li>hint 只包含 attachmentId / fileName / mimeType 三元组事实，不写工具用法</li>
 *   <li>hint 用 sentinel 包裹以便 AgentPersistenceHandler 剥离</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-22
 */
@ExtendWith(MockitoExtension.class)
class ChannelRuntimeIngressService_文档附件提示测试 {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_MIME = "application/pdf";

    @Mock private ChannelInstanceService channelInstanceService;
    @Mock private ChannelIngressService channelIngressService;
    @Mock private ConnectorRuntimeManager connectorRuntimeManager;
    @Mock private ChannelInstanceEventService channelInstanceEventService;
    @Mock private ChannelDeliveryDispatcher channelDeliveryDispatcher;
    @Mock private AttachmentRepository attachmentRepository;

    private ChannelRuntimeIngressService ingressService;
    private ChannelInstance activeInstance;
    private Path storageRoot;

    @BeforeEach
    void 初始化(@TempDir Path tmp) {
        storageRoot = tmp.resolve("attachments");
        ingressService = new ChannelRuntimeIngressService(
                channelInstanceService,
                channelIngressService,
                connectorRuntimeManager,
                channelInstanceEventService,
                channelDeliveryDispatcher,
                null, null,
                attachmentRepository,
                storageRoot.toString(),
                10L * 1024 * 1024,
                100
        );

        activeInstance = new ChannelInstance(
                "feishu.test",
                "feishu",
                "feishu",
                "飞书测试",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of(),
                Map.of(),
                null, null, null,
                Instant.parse("2026-04-02T10:00:00Z"),
                Instant.parse("2026-04-02T10:00:00Z")
        );
    }

    @Test
    @DisplayName("docx 附件 + text 消息 → 落盘 + save repo + hint 只含附件事实")
    void docx附件完整链路() throws Exception {
        setupMocks();
        when(attachmentRepository.saveForEntry(isNull(), eq("session-001"),
                eq("合同.docx"), any(String.class), any(Long.class),
                eq(DOCX_MIME), isNull()))
                .thenReturn("att-persisted-123");

        byte[] fakeDocx = "fake docx bytes".getBytes();
        String base64 = Base64.getEncoder().encodeToString(fakeDocx);
        var content = new ChannelRuntimeEventRequest.Content("text", "帮我改下合同第 3 段", null, null);
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                null, "合同.docx", DOCX_MIME, base64, fakeDocx.length);

        ingressService.processEvent("feishu.test", buildEventRequest(content, List.of(attachment)));

        GatewayMessage captured = captureGatewayMessage();

        // 1. content 是 TextMessage（非 FileMessage）
        assertThat(captured.content()).isInstanceOf(MessageContent.TextMessage.class);
        String text = ((MessageContent.TextMessage) captured.content()).text();

        // 2. hint 用 sentinel 包裹
        assertThat(text).contains(DocumentAttachmentHintBuilder.DOCUMENT_HINT_BEGIN);
        assertThat(text).contains(DocumentAttachmentHintBuilder.DOCUMENT_HINT_END);
        // 3. 包含用户原文 + 三元组事实（attachmentId / fileName / mimeType）
        assertThat(text).contains("帮我改下合同第 3 段");
        assertThat(text).contains("attachmentId=att-persisted-123");
        assertThat(text).contains("fileName=合同.docx");
        // 4. hint 不复述工具用法（是 schema 的职责）
        assertThat(text).doesNotContain("file.read");
        assertThat(text).doesNotContain("document.edit");

        // 5. GatewayMessage.attachments 用了 repo 返回的 id
        assertThat(captured.attachments()).hasSize(1);
        assertThat(captured.attachments().getFirst().attachmentId()).isEqualTo("att-persisted-123");

        // 7. 文件确实落盘到 storageRoot 目录
        try (var stream = Files.list(storageRoot)) {
            var files = stream.toList();
            assertThat(files).hasSize(1);
            assertThat(files.getFirst().getFileName().toString()).endsWith("_合同.docx");
            assertThat(Files.readAllBytes(files.getFirst())).isEqualTo(fakeDocx);
        }
    }

    @Test
    @DisplayName("pdf 附件同样走 attachmentId + mimeType 事实注入，不含任何工具用法")
    void pdf附件事实注入() {
        setupMocks();
        when(attachmentRepository.saveForEntry(isNull(), eq("session-001"),
                eq("paper.pdf"), any(String.class), any(Long.class),
                eq(PDF_MIME), isNull()))
                .thenReturn("att-pdf-456");

        byte[] fakePdf = "%PDF-fake".getBytes();
        String base64 = Base64.getEncoder().encodeToString(fakePdf);
        var content = new ChannelRuntimeEventRequest.Content("text", "读一下这个论文", null, null);
        var attachment = new ChannelRuntimeEventRequest.Attachment(
                null, "paper.pdf", PDF_MIME, base64, fakePdf.length);

        ingressService.processEvent("feishu.test", buildEventRequest(content, List.of(attachment)));

        String text = ((MessageContent.TextMessage) captureGatewayMessage().content()).text();
        assertThat(text).contains("attachmentId=att-pdf-456");
        assertThat(text).contains("mimeType=" + PDF_MIME);
        assertThat(text).doesNotContain("file.read");
        assertThat(text).doesNotContain("document.edit");
    }

    @Test
    @DisplayName("无附件的 text 消息：不注入 hint，原文 passthrough")
    void 无附件不注入hint() {
        setupMocks();

        var content = new ChannelRuntimeEventRequest.Content("text", "今天天气怎么样", null, null);

        ingressService.processEvent("feishu.test", buildEventRequest(content, List.of()));

        String text = ((MessageContent.TextMessage) captureGatewayMessage().content()).text();
        assertThat(text).isEqualTo("今天天气怎么样");
        assertThat(text).doesNotContain(DocumentAttachmentHintBuilder.DOCUMENT_HINT_BEGIN);
    }

    // ─── 辅助 ───────────────────────────────────

    private void setupMocks() {
        when(channelInstanceService.find("feishu.test")).thenReturn(Optional.of(activeInstance));
        var gatewayResponse = GatewayResponse.success(
                ChannelType.FEISHU, new ResponseContent.TextContent("ok"));
        when(channelIngressService.submitSync(any(GatewayMessage.class))).thenReturn(gatewayResponse);
        when(connectorRuntimeManager.markHeartbeat("feishu.test")).thenReturn(activeInstance);
        var eventResponse = new ChannelRuntimeEventResponse(true, "r1", 200, null, List.of());
        when(channelDeliveryDispatcher.buildEventResponse(any(), any(), any())).thenReturn(eventResponse);
    }

    private ChannelRuntimeEventRequest buildEventRequest(ChannelRuntimeEventRequest.Content content,
                                                          List<ChannelRuntimeEventRequest.Attachment> attachments) {
        return new ChannelRuntimeEventRequest(
                "evt-001", "msg-001", "user-001", "session-001",
                content, attachments,
                null, null, null,
                Instant.now()
        );
    }

    private GatewayMessage captureGatewayMessage() {
        ArgumentCaptor<GatewayMessage> captor = ArgumentCaptor.forClass(GatewayMessage.class);
        verify(channelIngressService).submitSync(captor.capture());
        return captor.getValue();
    }
}
