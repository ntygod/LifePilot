package com.lifepilot.document.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 会话级文档产物记录。
 *
 * <p>代表本地落盘的一份文档（docx / xlsx / pptx 等），origin 标识来源。
 * 和 message_attachments 的区别：session_documents 是文档资产的长期元数据，
 * message_attachments 负责把资产挂到特定消息气泡上做 UI 展示。</p>
 *
 * <p>命名前缀 {@code Session} 用于和 {@code com.lifepilot.knowledge} 模块的
 * 同名 {@code DocumentRecord}（知识库文档）区分 —— 对齐 session_documents
 * 表命名策略，避免包名歧义。</p>
 *
 * @param id         文档 UUID
 * @param sessionId  所属会话
 * @param entryId    关联的 transcript 条目 ID（assistant 消息），可为 null
 * @param fileName   用户可见文件名（含扩展名）
 * @param filePath   本地绝对路径
 * @param fileSize   文件大小（字节）
 * @param mimeType   MIME
 * @param origin     来源：agent_generated / user_upload / template_rendered
 * @param createdAt  创建时间（ISO 8601）
 * @author zsg
 * @since 2026-04-20
 */
public record SessionDocumentRecord(
        String id,
        String sessionId,
        @Nullable String entryId,
        String fileName,
        String filePath,
        long fileSize,
        String mimeType,
        String origin,
        Instant createdAt
) {

    public static final String ORIGIN_AGENT_GENERATED = "agent_generated";
    public static final String ORIGIN_USER_UPLOAD = "user_upload";
    public static final String ORIGIN_TEMPLATE_RENDERED = "template_rendered";
}
