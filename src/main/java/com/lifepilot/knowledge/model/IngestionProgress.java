package com.lifepilot.knowledge.model;

import java.util.Optional;

/**
 * 导入进度事件 — 追踪文档导入管线各阶段的进度。
 *
 * @param documentId      文档 ID
 * @param knowledgeBaseId 知识库 ID
 * @param stage           当前阶段
 * @param progressPercent 进度百分比（0-100）
 * @param message         进度消息（可选）
 * @author zsg
 * @since 2026-02-25
 */
public record IngestionProgress(
        String documentId,
        String knowledgeBaseId,
        DocumentStatus stage,
        int progressPercent,
        Optional<String> message
) {}
