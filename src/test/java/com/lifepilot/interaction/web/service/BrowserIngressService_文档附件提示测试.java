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
 * <p>验证：用户选择 docx/pdf 等文档附件时,BrowserIngressService 会在消息
 * content 末尾追加一段系统提示,列出附件名 + attachmentId,引导 LLM 调用
 * {@code file.read(attachmentId=...)} 读取内容。</p>
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
    void docx附件触发file_read系统提示(@TempDir Path tmp) throws Exception {
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
        assertThat(text).contains("file.read");
        assertThat(text).contains("attachmentId=att-doc");
    }

    @Test
    void hint使用marker包裹便于下游剥离(@TempDir Path tmp) throws Exception {
        Path docx = tmp.resolve("spec.docx");
        Files.createFile(docx);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-marker", ChatTurnAction.SEND, "解析需求文档",
                List.of("att-marker"), null));
        when(attachmentRepository.findById("att-marker")).thenReturn(new AttachmentRecord(
                "att-marker", "session-1", "spec.docx", docx.toString(),
                Files.size(docx),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "/api/attachments/att-marker"));

        var service = newService();
        var request = new ChatRequest("turn-marker", ChatTurnAction.SEND,
                "解析需求文档", "session-1", List.of("att-marker"), null);

        GatewayMessage msg = service.buildChatMessage(request, null, DeliveryMode.SYNC);
        var text = ((MessageContent.TextMessage) msg.content()).text();

        // 断言 hint 被 marker 包裹，且整段提示位于 marker 之间，便于下游剥离
        assertThat(text).contains(BrowserIngressService.DOCUMENT_HINT_BEGIN);
        assertThat(text).contains(BrowserIngressService.DOCUMENT_HINT_END);
        int begin = text.indexOf(BrowserIngressService.DOCUMENT_HINT_BEGIN);
        int end = text.indexOf(BrowserIngressService.DOCUMENT_HINT_END);
        assertThat(begin).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(begin);
        String wrapped = text.substring(begin, end);
        assertThat(wrapped).contains("file.read");
        assertThat(wrapped).contains("att-marker");
    }

    @Test
    void pdf附件触发file_read系统提示(@TempDir Path tmp) throws Exception {
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
        assertThat(text).contains("file.read");
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
        assertThat(text).doesNotContain("file.read");
        assertThat(text).isEqualTo("看这张图");
    }

    @Test
    void csv附件也触发file_read系统提示(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("data.csv");
        Files.createFile(csv);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-csv", ChatTurnAction.SEND, "分析这份数据",
                List.of("att-csv"), null));
        when(attachmentRepository.findById("att-csv")).thenReturn(new AttachmentRecord(
                "att-csv", "session-1", "data.csv", csv.toString(),
                Files.size(csv), "text/csv",
                "/api/attachments/att-csv"));

        var service = newService();
        var request = new ChatRequest("turn-csv", ChatTurnAction.SEND,
                "分析这份数据", "session-1", List.of("att-csv"), null);

        GatewayMessage msg = service.buildChatMessage(request, null, DeliveryMode.SYNC);
        var text = ((MessageContent.TextMessage) msg.content()).text();

        assertThat(text).contains("data.csv");
        assertThat(text).contains("file.read");
        assertThat(text).contains("att-csv");
        assertThat(text).contains(BrowserIngressService.DOCUMENT_HINT_BEGIN);
    }

    @Test
    void 混合类型附件只对文档生成提示(@TempDir Path tmp) throws Exception {
        Path docx = tmp.resolve("contract.docx");
        Path img = tmp.resolve("preview.png");
        Path pdf = tmp.resolve("report.pdf");
        Files.createFile(docx);
        Files.createFile(img);
        Files.createFile(pdf);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-mix", ChatTurnAction.SEND, "把这些资料整合一下",
                List.of("att-docx", "att-img", "att-pdf"), null));
        when(attachmentRepository.findById("att-docx")).thenReturn(new AttachmentRecord(
                "att-docx", "session-1", "contract.docx", docx.toString(),
                Files.size(docx),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "/api/attachments/att-docx"));
        when(attachmentRepository.findById("att-img")).thenReturn(new AttachmentRecord(
                "att-img", "session-1", "preview.png", img.toString(),
                Files.size(img), "image/png", "/api/attachments/att-img"));
        when(attachmentRepository.findById("att-pdf")).thenReturn(new AttachmentRecord(
                "att-pdf", "session-1", "report.pdf", pdf.toString(),
                Files.size(pdf), "application/pdf", "/api/attachments/att-pdf"));

        var service = newService();
        var request = new ChatRequest("turn-mix", ChatTurnAction.SEND,
                "把这些资料整合一下", "session-1",
                List.of("att-docx", "att-img", "att-pdf"), null);

        GatewayMessage msg = service.buildChatMessage(request, null, DeliveryMode.SYNC);
        var text = ((MessageContent.TextMessage) msg.content()).text();

        // hint 列出 docx 和 pdf
        assertThat(text).contains("contract.docx");
        assertThat(text).contains("att-docx");
        assertThat(text).contains("report.pdf");
        assertThat(text).contains("att-pdf");
        // 但不出现 png 文件名 / id
        assertThat(text).doesNotContain("preview.png");
        assertThat(text).doesNotContain("att-img");
        assertThat(text).contains("file.read");
    }

    @Test
    void xlsx附件触发file_read系统提示(@TempDir Path tmp) throws Exception {
        Path xlsx = tmp.resolve("sales.xlsx");
        Files.createFile(xlsx);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-xlsx", ChatTurnAction.SEND, "看这份销售表",
                List.of("att-xlsx"), null));
        when(attachmentRepository.findById("att-xlsx")).thenReturn(new AttachmentRecord(
                "att-xlsx", "session-1", "sales.xlsx", xlsx.toString(),
                Files.size(xlsx),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "/api/attachments/att-xlsx"));

        var service = newService();
        var request = new ChatRequest("turn-xlsx", ChatTurnAction.SEND,
                "看这份销售表", "session-1", List.of("att-xlsx"), null);

        GatewayMessage msg = service.buildChatMessage(request, null, DeliveryMode.SYNC);

        var text = ((MessageContent.TextMessage) msg.content()).text();
        assertThat(text).contains("sales.xlsx");
        assertThat(text).contains("file.read");
        assertThat(text).contains("att-xlsx");
    }

    @Test
    void pptx附件触发file_read系统提示(@TempDir Path tmp) throws Exception {
        Path pptx = tmp.resolve("deck.pptx");
        Files.createFile(pptx);

        when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
                "turn-pptx", ChatTurnAction.SEND, "看这个方案",
                List.of("att-pptx"), null));
        when(attachmentRepository.findById("att-pptx")).thenReturn(new AttachmentRecord(
                "att-pptx", "session-1", "deck.pptx", pptx.toString(),
                Files.size(pptx),
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "/api/attachments/att-pptx"));

        var service = newService();
        var request = new ChatRequest("turn-pptx", ChatTurnAction.SEND,
                "看这个方案", "session-1", List.of("att-pptx"), null);

        GatewayMessage msg = service.buildChatMessage(request, null, DeliveryMode.SYNC);

        var text = ((MessageContent.TextMessage) msg.content()).text();
        assertThat(text).contains("deck.pptx");
        assertThat(text).contains("file.read");
        assertThat(text).contains("att-pptx");
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
        assertThat(text).doesNotContain("file.read");
    }

    private BrowserIngressService newService() {
        BooleanSupplier audioProbe = () -> false;
        return new BrowserIngressService(
                attachmentRepository, chatTurnService, null, null,
                new MediaProperties(), audioProbe);
    }
}
