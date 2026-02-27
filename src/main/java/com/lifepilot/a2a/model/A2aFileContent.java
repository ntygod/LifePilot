package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

/**
 * A2A 文件内容。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aFileContent(
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String name,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String mimeType,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String bytes,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String uri
) {}
