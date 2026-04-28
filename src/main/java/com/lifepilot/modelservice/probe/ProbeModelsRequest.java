package com.lifepilot.modelservice.probe;

import org.springframework.lang.Nullable;

/**
 * 探测模型清单请求。
 *
 * <p>前端"挑 profile → 填 baseUrl/key → 拉 /v1/models"流程的入参；后端
 * {@link com.lifepilot.llm.adapter.AbstractProviderAdapter#healthCheck()}
 * 也复用本入参，把已注册 provider 的 profileId / apiUrl / apiKey 转交给
 * {@link ProbeModelsService#probe} 走毫秒级 GET，不再吃 chat ping 的超时。
 *
 * @param profileId 内置 ProviderProfile id（如 "deepseek-official" / "ollama-local"）
 * @param baseUrl   provider 基础地址（不含 /v1/models 之类的路径）
 * @param apiKey    provider 鉴权 key（Ollama 等无鉴权 provider 可传 null / 空串）
 * @author zsg
 * @since 2026-04-27
 */
public record ProbeModelsRequest(String profileId, String baseUrl, @Nullable String apiKey) {
}
