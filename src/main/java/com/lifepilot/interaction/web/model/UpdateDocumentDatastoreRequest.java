package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 更新知识库文档的 datastore 归属请求。
 *
 * @author zsg
 * @since 2026-03-27
 */
public record UpdateDocumentDatastoreRequest(
        @Nullable String datastoreId
) {
}
