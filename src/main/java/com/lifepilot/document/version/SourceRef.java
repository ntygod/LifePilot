package com.lifepilot.document.version;

/**
 * 文档来源引用 —— sealed interface，三种具体来源。
 *
 * <p>LLM 在 {@code document.edit} 工具里以 {@code source.type + value/id} 传入；
 * ActionDispatcher 反序列化为对应子类后传给 {@link DocumentVersionService#checkout}。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface SourceRef permits SourceRef.PathSource, SourceRef.AttachmentSource, SourceRef.DocumentSource {

    /** 本机路径源：LLM 给 D:/... 这类绝对路径。 */
    record PathSource(String path) implements SourceRef {
        public PathSource {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("PathSource.path 不能为空");
            }
        }
    }

    /** 附件源：前端拖拽上传产生的 attachmentId。 */
    record AttachmentSource(String attachmentId) implements SourceRef {
        public AttachmentSource {
            if (attachmentId == null || attachmentId.isBlank()) {
                throw new IllegalArgumentException("AttachmentSource.attachmentId 不能为空");
            }
        }
    }

    /** 已在 session_documents 表的文档 ID（包括 Phase 2 AI 产物，或 P3 已 checkout 过的）。 */
    record DocumentSource(String documentId) implements SourceRef {
        public DocumentSource {
            if (documentId == null || documentId.isBlank()) {
                throw new IllegalArgumentException("DocumentSource.documentId 不能为空");
            }
        }
    }
}
