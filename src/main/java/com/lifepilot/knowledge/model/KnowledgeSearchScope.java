package com.lifepilot.knowledge.model;

import org.springframework.lang.Nullable;

/**
 * 知识检索范围。
 *
 * <p>当 {@code datastoreId} 为空时，表示搜索整个知识库；
 * 否则仅搜索属于该 datastore 领域的文档。</p>
 *
 * @author zsg
 * @since 2026-03-26
 */
public record KnowledgeSearchScope(
        String knowledgeBaseId,
        @Nullable String datastoreId
) {
}
