package com.lifepilot.interaction.attachment;

import com.lifepilot.interaction.model.GatewayMessage;

import java.util.List;

/**
 * 文档类附件的 prompt 系统提示构造器 —— Web / Channel 两个 ingress 路径的共享工具。
 *
 * <p>用户消息中挂载了 docx / xlsx / pptx / pdf / md / txt / csv 等文档类附件时，
 * 在消息末尾追加 sentinel 包裹的系统提示，给 LLM 通用语义：</p>
 * <ul>
 *   <li>每个附件的 {@code attachmentId} + {@code fileName} + {@code mimeType}</li>
 *   <li>读取用 {@code file.read(attachmentId=...)}；修改用
 *       {@code document.edit(source={type:'attachment', id:'xxx'}, ...)}</li>
 * </ul>
 *
 * <p>hint 内容仅列通用工具用法，不对具体 case 做矫正（禁止"不要用 X"式否定句堆砌）。
 * LLM 出现具体行为错误应在系统层修根因（转换层 / 工具契约），不在 prompt 里打补丁。</p>
 *
 * <p>sentinel 包裹的目的：{@code AgentPersistenceHandler} 在持久化 user transcript 前
 * 依据 sentinel 剥离 hint，避免前端历史回显时把"系统提示"当用户原话展示。</p>
 *
 * @author zsg
 * @since 2026-04-22
 */
public final class DocumentAttachmentHintBuilder {

    /** 文档附件读取提示块起始 sentinel。持久化层依据该标记定位并剥离 hint。 */
    public static final String DOCUMENT_HINT_BEGIN = "<!--document-parse-hint-begin-->";

    /** 文档附件读取提示块结束 sentinel。 */
    public static final String DOCUMENT_HINT_END = "<!--document-parse-hint-end-->";

    /**
     * Phase 1B 可解析集合与 {@code knowledge/parser} 对齐：pdf / docx / xlsx / pptx / md / txt / csv。
     * 不包含 {@code application/msword}（.doc，WordParser 仅支持 docx）。
     */
    private static final List<String> DOCUMENT_MIME_PREFIXES = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/markdown",
            "text/plain",
            "text/csv"
    );

    /** docx / xlsx MIME — 用于判断 hint 是否提"可编辑"段落（pdf/txt 目前仅支持读）。 */
    private static final List<String> EDITABLE_DOCUMENT_MIME_PREFIXES = List.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private DocumentAttachmentHintBuilder() {}

    /**
     * 对含文档类附件的消息，在末尾追加系统提示；无文档附件时原样返回。
     *
     * @param originalContent 原始消息文本
     * @param attachments     当前轮次的全部附件
     * @return 追加提示后的文本
     */
    public static String appendHint(String originalContent,
                                     List<GatewayMessage.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return originalContent;
        }
        var docs = attachments.stream()
                .filter(DocumentAttachmentHintBuilder::isDocumentAttachment)
                .toList();
        if (docs.isEmpty()) {
            return originalContent;
        }
        boolean anyEditable = docs.stream().anyMatch(DocumentAttachmentHintBuilder::isEditableDocumentAttachment);

        var hint = new StringBuilder("\n\n").append(DOCUMENT_HINT_BEGIN)
                .append("\n[系统] 本轮附件：\n");
        for (var doc : docs) {
            hint.append("- attachmentId=").append(doc.attachmentId())
                    .append("; fileName=").append(doc.fileName())
                    .append("; mimeType=").append(doc.mimeType()).append('\n');
        }
        hint.append("工具：file.read(attachmentId) 读取；");
        if (anyEditable) {
            hint.append("document.edit(source={type:'attachment', id:attachmentId}) 修改；");
        }
        hint.append("file.read(path) 读本机路径。");
        hint.append(DOCUMENT_HINT_END);
        return originalContent + hint;
    }

    /** 判断单个附件是否属于文档类（需要 file.read 工具介入）。 */
    public static boolean isDocumentAttachment(GatewayMessage.Attachment att) {
        if (att.mimeType() == null) {
            return false;
        }
        return DOCUMENT_MIME_PREFIXES.stream().anyMatch(att.mimeType()::startsWith);
    }

    /** 判断附件是否可通过 {@code document.edit} 工具编辑（仅 docx / xlsx，pptx 暂无 edit 实现）。 */
    public static boolean isEditableDocumentAttachment(GatewayMessage.Attachment att) {
        if (att.mimeType() == null) {
            return false;
        }
        return EDITABLE_DOCUMENT_MIME_PREFIXES.stream().anyMatch(att.mimeType()::startsWith);
    }
}
