package com.lifepilot.knowledge.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档检索结果 — 混合检索返回的单条结果。
 *
 * @param chunkId          分块 ID
 * @param documentId       文档 ID
 * @param knowledgeBaseId  知识库 ID
 * @param content          分块内容
 * @param contextPrefix    上下文前缀（可选）
 * @param headingHierarchy 标题层级面包屑
 * @param score            相关性分数
 * @param sourcePath       来源路径（"vector" / "fts" / "fused"）
 * @param metadata         附加元数据
 * @param scoreBreakdown   分数来源明细（可选）
 * @param expandedContent  上下文窗口扩展后的完整内容（可选）
 * @author zsg
 * @since 2026-02-25
 */
public record DocumentSearchResult(
        String chunkId,
        String documentId,
        String knowledgeBaseId,
        String content,
        Optional<String> contextPrefix,
        List<String> headingHierarchy,
        double score,
        String sourcePath,
        Map<String, String> metadata,
        Optional<ScoreBreakdown> scoreBreakdown,
        Optional<String> expandedContent,
        DocumentSourceType sourceType
) {

    public DocumentSearchResult {
        if (scoreBreakdown == null) scoreBreakdown = Optional.empty();
        if (expandedContent == null) expandedContent = Optional.empty();
        if (sourceType == null) sourceType = DocumentSourceType.FILE;
    }

}
