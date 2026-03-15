package com.lifepilot.llm;

import com.lifepilot.llm.config.ProviderCapability;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * LlmRouter 统一请求参数对象。
 *
 * <p>不可变 record，通过 {@link #of(String, String)} 快捷创建或 {@link Builder} 构建复杂请求。
 * 紧凑构造器校验 scene/prompt 非 null，requiredCapability 为 null 时默认 {@link ProviderCapability#CHAT}。
 *
 * @author zsg
 * @since 2026-03-15
 */
public record LlmRequest(
        String scene,
        String prompt,
        @Nullable String outputSchema,
        @Nullable String modelName,
        @Nullable String preferredProviderId,
        ProviderCapability requiredCapability,
        @Nullable Duration timeoutOverride
) {

    public LlmRequest {
        Objects.requireNonNull(scene, "scene 不能为空");
        Objects.requireNonNull(prompt, "prompt 不能为空");
        if (requiredCapability == null) requiredCapability = ProviderCapability.CHAT;
    }

    /** 最简工厂方法。 */
    public static LlmRequest of(String scene, String prompt) {
        return new LlmRequest(scene, prompt, null, null, null, ProviderCapability.CHAT, null);
    }

    /** Builder 入口。 */
    public static Builder builder(String scene, String prompt) {
        return new Builder(scene, prompt);
    }

    public static final class Builder {
        private final String scene;
        private final String prompt;
        private String outputSchema;
        private String modelName;
        private String preferredProviderId;
        private ProviderCapability requiredCapability = ProviderCapability.CHAT;
        private Duration timeoutOverride;

        private Builder(String scene, String prompt) {
            this.scene = scene;
            this.prompt = prompt;
        }

        public Builder outputSchema(String outputSchema) { this.outputSchema = outputSchema; return this; }
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }
        public Builder preferredProviderId(String id) { this.preferredProviderId = id; return this; }
        public Builder requiredCapability(ProviderCapability cap) { this.requiredCapability = cap; return this; }
        public Builder timeoutOverride(Duration timeout) { this.timeoutOverride = timeout; return this; }

        public LlmRequest build() {
            return new LlmRequest(scene, prompt, outputSchema, modelName,
                    preferredProviderId, requiredCapability, timeoutOverride);
        }
    }
}
