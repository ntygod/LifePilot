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
    @DisplayName("docx 附件注入 sentinel 包裹的三元组事实")
    void docx附件注入结构化事实() {
        var attachments = List.of(att("a1", "contract.docx", DOCX_MIME));
        String text = DocumentAttachmentHintBuilder.appendHint("改一下合同", attachments);

        assertThat(text).contains("改一下合同");
        assertThat(text).contains(DocumentAttachmentHintBuilder.DOCUMENT_HINT_BEGIN);
        assertThat(text).contains(DocumentAttachmentHintBuilder.DOCUMENT_HINT_END);
        assertThat(text).contains("attachmentId=a1");
        assertThat(text).contains("fileName=contract.docx");
        assertThat(text).contains("mimeType=" + DOCX_MIME);
        // hint 不复述工具用法（工具用法是 schema 的职责），避免 separation-of-concerns 漂移
        assertThat(text).doesNotContain("file.read");
        assertThat(text).doesNotContain("document.edit");
    }

    @Test
    @DisplayName("xlsx / pdf / csv 所有文档类型一视同仁注入事实，MIME 差异不影响 hint 形态")
    void 文档类附件一致注入() {
        var attachments = List.of(
                att("x1", "report.xlsx", XLSX_MIME),
                att("p1", "paper.pdf", PDF_MIME)
        );
        String text = DocumentAttachmentHintBuilder.appendHint("", attachments);

        assertThat(text).contains("attachmentId=x1");
        assertThat(text).contains("attachmentId=p1");
        assertThat(text).contains("mimeType=" + XLSX_MIME);
        assertThat(text).contains("mimeType=" + PDF_MIME);
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
    @DisplayName("空附件列表 / null 返回原文")
    void 空附件原样返回() {
        assertThat(DocumentAttachmentHintBuilder.appendHint("hello", List.of())).isEqualTo("hello");
        assertThat(DocumentAttachmentHintBuilder.appendHint("hello", null)).isEqualTo("hello");
    }
}
