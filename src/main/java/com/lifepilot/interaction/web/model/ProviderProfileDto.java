package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * Provider Profile 元数据 DTO，用于前端模型服务编辑页选择 profile。
 *
 * @author zsg
 * @since 2026-04-27
 */
public record ProviderProfileDto(
        String id,
        String displayName,
        String baseAdapter,
        String defaultBaseUrl,
        String thinkingProtocol,
        List<String> capabilities
) {
}
