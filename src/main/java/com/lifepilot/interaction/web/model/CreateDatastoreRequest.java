package com.lifepilot.interaction.web.model;

import com.lifepilot.datastore.model.FieldHint;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Datastore 创建请求。
 *
 * @author zsg
 * @since 2026-04-13
 */
public record CreateDatastoreRequest(
        String name,
        @Nullable String description,
        boolean timeSeries,
        @Nullable List<FieldHint> fieldHints
) {}
