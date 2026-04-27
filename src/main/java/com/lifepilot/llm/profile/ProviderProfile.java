package com.lifepilot.llm.profile;

import com.lifepilot.llm.config.ProviderCapability;

import java.util.Objects;
import java.util.Set;

/**
 * Provider 协议特性数据载体 — 描述一个 provider 的所有协议维度。
 *
 * <p>本 record 是数据驱动设计的核心：协议差异不再散落在 Adapter 代码的 if-else 里，
 * 而是集中由 ProviderProfile 字段描述。新增 provider 通常只需新增一个 ProviderProfile
 * 常量，不需要修改主流程代码。
 *
 * @author zsg
 * @since 2026-04-27
 */
public record ProviderProfile(
        String id,
        String displayName,
        BaseAdapterType baseAdapter,
        String defaultBaseUrl,
        ThinkingProtocolId thinkingProtocol,
        StructuredOutputMode structuredOutput,
        PromptCacheStrategyId cacheStrategy,
        ModelDiscoveryEndpoint modelDiscovery,
        Set<ProviderCapability> capabilities,
        MultiTurnHistoryRules historyRules
) {
    public ProviderProfile {
        Objects.requireNonNull(id, "Profile id 不能为空");
        Objects.requireNonNull(displayName, "displayName 不能为空");
        Objects.requireNonNull(baseAdapter, "baseAdapter 不能为空");
        Objects.requireNonNull(defaultBaseUrl, "defaultBaseUrl 不能为空");
        Objects.requireNonNull(thinkingProtocol, "thinkingProtocol 不能为空");
        Objects.requireNonNull(structuredOutput, "structuredOutput 不能为空");
        Objects.requireNonNull(cacheStrategy, "cacheStrategy 不能为空");
        Objects.requireNonNull(modelDiscovery, "modelDiscovery 不能为空");
        Objects.requireNonNull(capabilities, "capabilities 不能为空");
        Objects.requireNonNull(historyRules, "historyRules 不能为空");
        capabilities = Set.copyOf(capabilities);
    }
}
