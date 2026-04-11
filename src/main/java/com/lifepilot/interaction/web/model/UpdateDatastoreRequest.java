package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * Datastore 更新请求。
 *
 * @author zsg
 * @since 2026-04-11
 */
public record UpdateDatastoreRequest(
        @Nullable String description,
        @Nullable String metadataJson,
        @Nullable String projectionConfigJson
) {
}
