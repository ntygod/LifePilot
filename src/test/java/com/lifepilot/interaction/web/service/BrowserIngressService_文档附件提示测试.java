package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository.AttachmentRecord;
import com.lifepilot.interaction.web.service.ChatTurnService.ResolvedTurnRequest;
import com.lifepilot.media.config.MediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 文档附件提示注入测试。
 *
 * <p>验证：用户上传 docx/pdf 等文档附件时,BrowserIngressService 会在消息
 * content 末尾追加一段系统提示,列出附件名 + attachmentId,引导 LLM 调用
 * {@code document.parse} 工具读取内容。</p>
 *
 * <p>非文档附件(如图片)不触发该提示,避免干扰多模态路由。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class BrowserIngressService_文档附件提示测试 {

    @Mock
    AttachmentRepository attachmentRepository;

    @Mock
    ChatTurnService chatTurnService;

    @Test
    void docx附件触发document_parse系统提示(@TempDir Path tmp) throws Exception {
        Path docx = tmp.resolve("contract.docx");
        Files.createFile(docx);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-1", ChatTurnAction.SEND, "请帮我读这份合同",
                List.of("att-doc"), null));
        when(attachmentRepository.findById("att-doc")).thenReturn(new AttachmentRecord(
                "att-doc", "session-1", "contract.docx", docx.toString(),
                Files.size(docx),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "/api/attachments/att-doc"));

        var service = newService();
        var request = new ChatRequest("turn-1", ChatTurnAction.SEND,
                "请帮我读这份合同", "session-1", List.of("att-doc"), null);

        GatewayMessage msg = service.buildChatMessage(request, null, DeliveryMode.SYNC);

        var text = ((MessageContent.TextMessage) msg.content()).text();
        assertThat(text).contains("请帮我读这份合同");
        assertThat(text).contains("contract.docx");
        assertThat(text).contains("document.parse");
        assertThat(text).contains("att-doc");
    }

    @Test
    void pdf附件触发document_parse系统提示(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("paper.pdf");
        Files.createFile(pdf);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-pdf", ChatTurnAction.SEND, "总结这篇论文",
                List.of("att-pdf"), null));
        when(attachmentRepository.findById("att-pdf")).thenReturn(new AttachmentRecord(
                "att-pdf", "session-1", "paper.pdf", pdf.toString(),
                Files.size(pdf), "application/pdf",
                "/api/attachments/att-pdf"));

        var service = newService();
        var request = new ChatRequest("turn-pdf", ChatTurnAction.SEND,
                "总结这篇论文", "session-1", List.of("att-pdf"), null);

        GatewayMessage msg = service.buildChatMessage(request, null, DeliveryMode.SYNC);

        var text = ((MessageContent.TextMessage) msg.content()).text();
        assertThat(text).contains("paper.pdf");
        assertThat(text).contains("document.parse");
        assertThat(text).contains("att-pdf");
    }

    @Test
    void 图片附件不触发文档提示(@TempDir Path tmp) throws Exception {
        Path img = tmp.resolve("photo.png");
        Files.createFile(img);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-2", ChatTurnAction.SEND, "看这张图",
                List.of("att-img"), null));
        when(attachmentRepository.findById("att-img")).thenReturn(new AttachmentRecord(
                "att-img", "session-1", "photo.png", img.toString(),
                Files.size(img), "image/png", "/api/attachments/att-img"));

        var service = newService();
        var request = new ChatRequest("turn-2", ChatTurnAction.SEND,
                "看这张图", "session-1", List.of("att-img"), null);

        GatewayMessage msg = service.buildChatMessage(request, null, DeliveryMode.SYNC);

        var text = ((MessageContent.TextMessage) msg.content()).text();
        assertThat(text).doesNotContain("document.parse");
        assertThat(text).isEqualTo("看这张图");
    }

    @Test
    void 无附件时不注入任何提示() {
        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-3", ChatTurnAction.SEND, "你好",
                null, null));

        var service = newService();
        var request = new ChatRequest("turn-3", ChatTurnAction.SEND,
                "你好", "session-1", null, null);

        GatewayMessage msg = service.buildChatMessage(request, null, DeliveryMode.SYNC);

        var text = ((MessageContent.TextMessage) msg.content()).text();
        assertThat(text).isEqualTo("你好");
        assertThat(text).doesNotContain("document.parse");
    }

    private BrowserIngressService newService() {
        BooleanSupplier audioProbe = () -> false;
        return new BrowserIngressService(
                attachmentRepository, chatTurnService, null, null,
                new MediaProperties(), audioProbe);
    }
}
