package com.lifepilot.document.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 文档版本记录 —— document_versions 表一行。
 *
 * <p>每次 patch / rollback 产生一条，初始 checkout 时写 source=initial 的 v0。
 * {@code diffJson} 缓存前端渲染用的 diff 结构；{@code patchSummary} 是一句话摘要。</p>
 *
 * @param id             版本记录 UUID
 * @param documentId     所属 session_documents.id
 * @param versionNo      版本号（0 = initial checkout；递增 1/2/3...）
 * @param filePath       该版本物理文件绝对路径（{@code working/{documentId}/v{n}.docx}）
 * @param source         initial / patch / rollback
 * @param patchSummary   可空；patch 时给用户的摘要,如 "共 3 处修改：replace_text x2, insert_paragraph_after x1"
 * @param diffJson       可空；patch 时缓存的 diff JSON 字符串
 * @param createdAt      ISO 8601
 * @author zsg
 * @since 2026-04-21
 */
public record DocumentVersionRecord(
        String id,
        String documentId,
        int versionNo,
        String filePath,
        String source,
        @Nullable String patchSummary,
        @Nullable String diffJson,
        Instant createdAt
) {

    public static final String SOURCE_INITIAL = "initial";
    public static final String SOURCE_PATCH = "patch";
    public static final String SOURCE_ROLLBACK = "rollback";
}
