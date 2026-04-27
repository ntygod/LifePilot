package com.lifepilot.llm.profile;

import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 模型探测端点协议描述。
 *
 * <p>用于 ProbeModelsService 按 profile 配置发起 GET 请求，解析 provider
 * 返回的 model 清单。OpenAI 兼容 provider 走 `/v1/models`，Ollama 走 `/api/tags`。
 *
 * @param path                       端点路径，如 "/v1/models"
 * @param authHeaderName             鉴权 Header 名称，如 "Authorization"
 * @param authHeaderFormat           鉴权 Header 值模板，如 "Bearer ${apiKey}"
 * @param responseModelsJsonPath     响应中 models 数组的 JsonPath，如 "$.data[*].id"
 * @param responseModelNameJsonPath  响应中 model 名称的 JsonPath（null 时用 id）
 * @author zsg
 * @since 2026-04-27
 */
public record ModelDiscoveryEndpoint(
        String path,
        String authHeaderName,
        String authHeaderFormat,
        String responseModelsJsonPath,
        @Nullable String responseModelNameJsonPath
) {
    public ModelDiscoveryEndpoint {
        Objects.requireNonNull(path, "path 不能为空");
        Objects.requireNonNull(authHeaderName, "authHeaderName 不能为空");
        Objects.requireNonNull(authHeaderFormat, "authHeaderFormat 不能为空");
        Objects.requireNonNull(responseModelsJsonPath, "responseModelsJsonPath 不能为空");
    }

    /** OpenAI 兼容默认配置：/v1/models + Authorization: Bearer + $.data[*].id */
    public static ModelDiscoveryEndpoint openAiCompatible() {
        return new ModelDiscoveryEndpoint(
                "/v1/models",
                "Authorization",
                "Bearer ${apiKey}",
                "$.data[*].id",
                null
        );
    }

    /** Ollama：/api/tags + 无鉴权 + $.models[*].name */
    public static ModelDiscoveryEndpoint ollama() {
        return new ModelDiscoveryEndpoint(
                "/api/tags",
                "X-Unused",
                "${apiKey}",
                "$.models[*].name",
                null
        );
    }
}
