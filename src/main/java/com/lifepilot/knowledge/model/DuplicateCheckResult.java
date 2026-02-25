package com.lifepilot.knowledge.model;

import java.util.Optional;

/**
 * 重复检测结果 — 记录文档内容哈希和重复状态。
 *
 * @param isDuplicate        是否重复
 * @param contentHash        SHA-256 内容哈希
 * @param existingDocumentId 已存在的重复文档 ID（可选）
 * @author zsg
 * @since 2026-02-25
 */
public record DuplicateCheckResult(
        boolean isDuplicate,
        String contentHash,
        Optional<String> existingDocumentId
) {}
