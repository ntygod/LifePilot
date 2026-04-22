package com.lifepilot.interaction.attachment;

import com.lifepilot.interaction.model.GatewayMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentAttachmentHintBuilder 单元测试。
 *
 * @author zsg
 * @since 2026-04-22
 */
class DocumentAttachmentHintBuilder_单元测试 {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PDF_MIME = "application/pdf";
    private static final String PNG_MIME = "image/png";

    private static GatewayMessage.Attachment att(String id, String fileName, String mime) {
        return new GatewayMessage.Attachment(id, fileName, mime, new byte[0], 0L);
    }

    @Test
    @DisplayName("docx 附件注入 hint：含 file.read 引导 + document.edit 引导 + attachmentId")
    void docx附件注入含文件读写双引导() {
        var attachments = List.of(att("a1", "contract.docx", DOCX_MIME));
        String text = DocumentAttachmentHintBuilder.appendHint("改一下合同", attachments);

        assertThat(text).contains("改一下合同");
        assertThat(text).contains(DocumentAttachmentHintBuilder.DOCUMENT_HINT_BEGIN);
        assertThat(text).contains(DocumentAttachmentHintBuilder.DOCUMENT_HINT_END);
        assertThat(text).contains("attachmentId=a1");
        assertThat(text).contains("contract.docx");
        // 两种引导都要有
        assertThat(text).contains("file.read(attachmentId=...)");
        assertThat(text).contains("document.edit(source={type:'attachment'");
        // 反模式提醒
        assertThat(text).contains("不要用 document.create 重新生成");
    }

    @Test
    @DisplayName("xlsx 附件也给 document.edit 引导（可编辑 MIME）")
    void xlsx附件给document_edit引导() {
        var attachments = List.of(att("x1", "report.xlsx", XLSX_MIME));
        String text = DocumentAttachmentHintBuilder.appendHint("加一行汇总", attachments);

        assertThat(text).contains("document.edit(source={type:'attachment'");
        assertThat(text).contains("report.xlsx");
    }

    @Test
    @DisplayName("pdf 附件只给 file.read 引导（pdf 目前不可 edit）")
    void pdf附件仅给file_read() {
        var attachments = List.of(att("p1", "paper.pdf", PDF_MIME));
        String text = DocumentAttachmentHintBuilder.appendHint("读一下论文", attachments);

        assertThat(text).contains("file.read(attachmentId=...)");
        assertThat(text).contains("paper.pdf");
        // pdf 没有 document.edit 支持 —— 不应出现 edit 引导
        assertThat(text).doesNotContain("document.edit(source={type:'attachment'");
    }

    @Test
    @DisplayName("图片附件不触发 hint（不属于文档类）")
    void 图片附件不注入() {
        var attachments = List.of(att("i1", "photo.png", PNG_MIME));
        String text = DocumentAttachmentHintBuilder.appendHint("帮我看这张图", attachments);

        assertThat(text).isEqualTo("帮我看这张图");
        assertThat(text).doesNotContain(DocumentAttachmentHintBuilder.DOCUMENT_HINT_BEGIN);
    }

    @Test
    @DisplayName("混合附件：至少一个可编辑文档时给出 edit 引导")
    void 混合附件给edit引导() {
        var attachments = List.of(
                att("p1", "paper.pdf", PDF_MIME),
                att("d1", "contract.docx", DOCX_MIME)
        );
        String text = DocumentAttachmentHintBuilder.appendHint("看一下然后改合同", attachments);

        assertThat(text).contains("paper.pdf");
        assertThat(text).contains("contract.docx");
        assertThat(text).contains("document.edit(source={type:'attachment'");
    }

    @Test
    @DisplayName("空附件列表 / null 返回原文")
    void 空附件原样返回() {
        assertThat(DocumentAttachmentHintBuilder.appendHint("hello", List.of())).isEqualTo("hello");
        assertThat(DocumentAttachmentHintBuilder.appendHint("hello", null)).isEqualTo("hello");
    }

    @Test
    @DisplayName("isEditableDocumentAttachment：仅 docx / xlsx 为 true")
    void 可编辑判定正确() {
        assertThat(DocumentAttachmentHintBuilder.isEditableDocumentAttachment(
                att("x", "a.docx", DOCX_MIME))).isTrue();
        assertThat(DocumentAttachmentHintBuilder.isEditableDocumentAttachment(
                att("x", "a.xlsx", XLSX_MIME))).isTrue();
        assertThat(DocumentAttachmentHintBuilder.isEditableDocumentAttachment(
                att("x", "a.pdf", PDF_MIME))).isFalse();
        assertThat(DocumentAttachmentHintBuilder.isEditableDocumentAttachment(
                att("x", "a.png", PNG_MIME))).isFalse();
    }
}
