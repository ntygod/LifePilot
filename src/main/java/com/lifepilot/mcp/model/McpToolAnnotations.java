package com.lifepilot.mcp.model;

import jakarta.annotation.Nullable;

/**
 * MCP 工具注解（MCP 规范扩展字段）。
 *
 * <p>用于传递工具的元信息，如风险等级、幂等性等。</p>
 *
 * @param title 工具标题
 * @param readOnlyHint 是否只读
 * @param destructiveHint 是否有破坏性
 * @param idempotentHint 是否幂等
 * @param openWorldHint 是否访问外部世界
 * @author zsg
 * @since 2026-02-24
 */
public record McpToolAnnotations(
        @Nullable String title,
        @Nullable Boolean readOnlyHint,
        @Nullable Boolean destructiveHint,
        @Nullable Boolean idempotentHint,
        @Nullable Boolean openWorldHint
) {}
