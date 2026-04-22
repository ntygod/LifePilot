package com.lifepilot.interaction.attachment;

import com.lifepilot.interaction.model.GatewayMessage;

import java.util.List;

/**
 * 文档类附件的 prompt 结构化事实注入器 —— Web / Channel 两个 ingress 路径的共享工具。
 *
 * <p>{@code GatewayMessage.attachments} 是结构化字段但 LLM 看到的是纯文本 UserMessage，
 * 附件信息不会自动进 prompt。这里的职责：把本轮文档类附件的 {@code attachmentId /
 * fileName / mimeType} 三个字段注入到消息末尾（sentinel 包裹），让 LLM 知道"本轮上下文里
 * 有哪些附件可引用"。</p>
 *
 * <p><b>只放事实，不放用法</b>：如何用 {@code attachmentId} 调 {@code file.read} 或
 * {@code document.edit} 是工具自己 schema description 的职责。这里复述工具用法会：</p>
 * <ul>
 *   <li>违反 Schema/Skill/Hint 的 separation of concerns（用法散落多处易漂移）</li>
 *   <li>变成"为了让 LLM 会用工具"的单点矫正（见 memory feedback_no_prompt_patching）</li>
 * </ul>
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

    private DocumentAttachmentHintBuilder() {}

    /**
     * 对含文档类附件的消息，在末尾追加结构化附件事实；无文档附件时原样返回。
     *
     * @param originalContent 原始消息文本
     * @param attachments     当前轮次的全部附件
     * @return 追加事实后的文本
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

        var hint = new StringBuilder("\n\n").append(DOCUMENT_HINT_BEGIN)
                .append("\n[系统] 本轮附件：\n");
        for (var doc : docs) {
            hint.append("- attachmentId=").append(doc.attachmentId())
                    .append("; fileName=").append(doc.fileName())
                    .append("; mimeType=").append(doc.mimeType()).append('\n');
        }
        hint.append(DOCUMENT_HINT_END);
        return originalContent + hint;
    }

    /** 判断单个附件是否属于文档类（参与 hint 注入的 MIME 白名单）。 */
    public static boolean isDocumentAttachment(GatewayMessage.Attachment att) {
        if (att.mimeType() == null) {
            return false;
        }
        return DOCUMENT_MIME_PREFIXES.stream().anyMatch(att.mimeType()::startsWith);
    }
}
