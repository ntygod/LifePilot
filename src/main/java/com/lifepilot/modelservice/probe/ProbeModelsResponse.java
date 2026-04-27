package com.lifepilot.modelservice.probe;

import java.util.List;
import java.util.Objects;

/**
 * 探测模型清单响应。
 *
 * <p>按 {@link com.lifepilot.llm.profile.ModelDiscoveryEndpoint} 配置的
 * jsonPath 从 provider 返回体提取出的 model 列表；前端"选模型"下拉框
 * 直接消费 {@link ModelInfo#id()}，{@link ModelInfo#name()} 给将来扩展
 * 展示别名留接口（当前两者一致，由 service 实现填充）。
 *
 * @param models 模型清单（不可变；空列表代表 provider 未返回任何模型）
 * @author zsg
 * @since 2026-04-27
 */
public record ProbeModelsResponse(List<ModelInfo> models) {

    public ProbeModelsResponse {
        Objects.requireNonNull(models, "models 不能为空");
        models = List.copyOf(models);
    }

    /**
     * 单个模型条目。
     *
     * @param id   模型唯一标识（用于实际 API 调用）
     * @param name 展示名称（当前与 id 一致，预留 displayName 扩展点）
     */
    public record ModelInfo(String id, String name) {
        public ModelInfo {
            Objects.requireNonNull(id, "id 不能为空");
            Objects.requireNonNull(name, "name 不能为空");
        }
    }
}
