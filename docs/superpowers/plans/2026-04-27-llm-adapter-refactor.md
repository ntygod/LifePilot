# LLM 适配器层重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `SpringAiProviderAdapter` 单类内 host 字符串分流的 LLM 适配层，重构为 `ProviderProfile` 数据驱动 + Adapter 多态 + `LlmStreamEvent` 流式事件流的新架构，原生支持 DeepSeek V4 / Qwen3 / OpenAI o-系列 / Anthropic thinking 多轮契约，整合 `/v1/models` 探测式模型配置。

**Architecture:** 协议特性写到 `ProviderProfile` record 数据驱动；Adapter 按 `BaseAdapterType` 多态拆分（OpenAiBase / Anthropic / Ollama），thinking 协议差异由 `ThinkingProtocol` 接口的 5 个实现承载；流式从 `Flux<String>` 升级到 `Flux<LlmStreamEvent>` sealed interface；多轮 history 注入由 `ChatHistoryAssembler` 按 profile 规则序列化。

**Tech Stack:** Java 22 + Spring AI 1.1.3 + Spring Boot + SQLite (Flyway) + JUnit 5 + jqwik + Reactor + Vue 3 + Pinia + Reka UI + Tailwind 4。

**Spec:** [`docs/superpowers/specs/2026-04-27-llm-adapter-refactor-design.md`](../specs/2026-04-27-llm-adapter-refactor-design.md)

---

## File Structure

### 新增文件（19）

```
src/main/java/com/lifepilot/llm/
├── profile/
│   ├── BaseAdapterType.java                    （枚举）
│   ├── ThinkingProtocolId.java                 （枚举）
│   ├── StructuredOutputMode.java               （枚举）
│   ├── PromptCacheStrategyId.java              （枚举）
│   ├── ReasoningInjectionFormat.java           （枚举）
│   ├── ModelDiscoveryEndpoint.java             （record）
│   ├── MultiTurnHistoryRules.java              （record）
│   ├── ProviderProfile.java                    （record）
│   ├── ProviderProfileRegistry.java            （Spring bean）
│   └── BuiltinProviderProfiles.java            （常量类）
├── thinking/
│   ├── ThinkingMode.java                       （枚举）
│   ├── RequestBuilder.java                     （可变请求构造类）
│   ├── ThinkingProtocol.java                   （接口）
│   ├── NoopThinkingProtocol.java
│   ├── DeepSeekThinkingProtocol.java
│   ├── QwenThinkingProtocol.java
│   ├── OpenAiReasoningEffortProtocol.java
│   └── AnthropicThinkingProtocol.java
├── adapter/
│   ├── AbstractProviderAdapter.java            （新增基类）
│   ├── OpenAiBaseProviderAdapter.java
│   ├── DeepSeekProviderAdapter.java
│   ├── QwenProviderAdapter.java
│   ├── OpenAiOfficialProviderAdapter.java
│   ├── AnthropicProviderAdapter.java
│   └── OllamaProviderAdapter.java
├── stream/
│   ├── LlmStreamEvent.java                     （sealed interface）
│   ├── ReasoningChunk.java                     （record）
│   ├── ContentChunk.java                       （record）
│   ├── ToolCallDelta.java                      （record）
│   ├── UsageEvent.java                         （record）
│   ├── DoneEvent.java                          （record）
│   └── ErrorEvent.java                         （record）
├── history/
│   ├── ProviderMessage.java                    （record）
│   ├── AssistantMessageBuilder.java
│   └── ChatHistoryAssembler.java
└── ToolCall.java                               （record）

src/main/java/com/lifepilot/modelservice/probe/
├── ProbeModelsRequest.java
├── ProbeModelsResponse.java
└── ProbeModelsService.java

src/main/resources/db/migration/V32__model_service_provider_profile.sql

zhiwei-web/src/components/chat/ReasoningSection.vue
zhiwei-web/src/composables/useChatStream.ts                （重写）
zhiwei-web/src/api/providerProfile.ts                     （新增）
zhiwei-web/src/api/probeModels.ts                         （新增）
```

### 修改文件（17）

```
src/main/java/com/lifepilot/llm/
├── LlmResponse.java                            （重写：富字段化）
├── StreamingLlmResponse.java                   （重写：包装 Flux<LlmStreamEvent>）
├── adapter/
│   ├── ProviderAdapter.java                    （sealed permits 改）
│   ├── ProviderAdapterFactory.java             （改写：按 profile 路由）
│   └── SpringAiProviderAdapter.java            （删除）
├── config/
│   ├── ProviderType.java                       （改：仅作 baseAdapter 标记）
│   └── ProviderConfig.java                     （加 profileId 字段）

src/main/java/com/lifepilot/agent/callback/
├── StreamingCallback.java                      （改：消费 LlmStreamEvent）
└── NonStreamingCallback.java                   （改：消费富 LlmResponse）

src/main/java/com/lifepilot/agent/streaming/
└── StreamingEventHandler.java                  （改：SSE 多事件类型）

src/main/java/com/lifepilot/generation/router/GenerationRouter.java（小改）
src/main/java/com/lifepilot/agent/ReactAgentLoop.java（消费富 LlmResponse）
src/main/java/com/lifepilot/interaction/web/service/ChatTurnService.java（payload_json 扩展）
src/main/java/com/lifepilot/interaction/web/service/ChatSessionService.java（反序列化）
src/main/java/com/lifepilot/agent/persistence/AgentPersistenceHandler.java（reasoning history 续跑）
src/main/java/com/lifepilot/modelservice/model/ModelServiceEntity.java（重写）
src/main/java/com/lifepilot/modelservice/repository/ModelServiceRepository.java（适配新字段）
src/main/java/com/lifepilot/interaction/web/controller/ModelServiceController.java（加 probe endpoint）

zhiwei-web/src/views/settings/ModelServiceManager.vue（重写）
zhiwei-web/src/components/chat/MessageBubble.vue（加 reasoning 区域）
```

---

## Phase 1：ProviderProfile 数据驱动层（commit 1）

**目标**：建立协议特性数据描述层，所有协议差异落到 `ProviderProfile` 数据载体。

### Task 1.1：创建 5 个枚举类型

**Files:**
- Create: `src/main/java/com/lifepilot/llm/profile/BaseAdapterType.java`
- Create: `src/main/java/com/lifepilot/llm/profile/ThinkingProtocolId.java`
- Create: `src/main/java/com/lifepilot/llm/profile/StructuredOutputMode.java`
- Create: `src/main/java/com/lifepilot/llm/profile/PromptCacheStrategyId.java`
- Create: `src/main/java/com/lifepilot/llm/profile/ReasoningInjectionFormat.java`

- [ ] **Step 1：创建 BaseAdapterType 枚举**

```java
package com.lifepilot.llm.profile;

/**
 * 基础 SDK 适配器类型 — 决定使用哪条 Spring AI ChatModel 路径。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum BaseAdapterType {
    /** OpenAI 兼容 API（DeepSeek / Qwen / OpenAI 官方 / 智谱 / 月之暗面 / 火山等共用） */
    OPENAI_BASE,
    /** Anthropic 原生 API */
    ANTHROPIC_BASE,
    /** 本地 Ollama 服务 */
    OLLAMA,
    /** HuggingFace TEI（embedding / rerank 专用） */
    TEI
}
```

- [ ] **Step 2：创建 ThinkingProtocolId 枚举**

```java
package com.lifepilot.llm.profile;

/**
 * 推理模式协议标识 — 用于路由到具体 ThinkingProtocol 实现。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum ThinkingProtocolId {
    /** DeepSeek V4 系列：extra_body.thinking + reasoning_content 多轮回传 */
    DEEPSEEK,
    /** Qwen3 系列：chat_template_kwargs.enable_thinking + reasoning_content */
    QWEN,
    /** OpenAI o-系列 / GPT-5：reasoning.effort 参数（响应不返回 reasoning） */
    OPENAI_REASONING_EFFORT,
    /** Anthropic Claude 4.x：thinking block + signature 验证回传 */
    ANTHROPIC,
    /** 非推理模型 / 关闭模式 */
    NONE
}
```

- [ ] **Step 3：创建 StructuredOutputMode 枚举**

```java
package com.lifepilot.llm.profile;

/**
 * 结构化输出协议支持级别。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum StructuredOutputMode {
    /** 完整 JSON Schema 模式（OpenAI 官方 / 通义） */
    JSON_SCHEMA,
    /** JSON Object 模式（DeepSeek / 智谱 / 火山等） */
    JSON_OBJECT,
    /** 协议不支持，回退到提示词约束 */
    PROMPT_ONLY
}
```

- [ ] **Step 4：创建 PromptCacheStrategyId 枚举**

```java
package com.lifepilot.llm.profile;

/**
 * Prompt 缓存策略标识 — 与 com.lifepilot.llm.cache.PromptCacheStrategy 实现对应。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum PromptCacheStrategyId {
    ANTHROPIC_EPHEMERAL,
    DASHSCOPE_EXPLICIT,
    OPENAI_AUTO,
    NOOP
}
```

- [ ] **Step 5：创建 ReasoningInjectionFormat 枚举**

```java
package com.lifepilot.llm.profile;

/**
 * 多轮 history 中 reasoning 内容的注入格式。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum ReasoningInjectionFormat {
    /** assistant message 平级加 reasoning_content 字段（DeepSeek / Qwen） */
    CONTENT_ONLY,
    /** 通过 extra_body 等私有字段注入（保留位） */
    EXTRA_BODY,
    /** content array 第一块为 thinking block（Anthropic） */
    THINKING_BLOCK
}
```

- [ ] **Step 6：编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS（仅 5 个新枚举，不影响现有代码）

### Task 1.2：创建 ModelDiscoveryEndpoint 与 MultiTurnHistoryRules record

**Files:**
- Create: `src/main/java/com/lifepilot/llm/profile/ModelDiscoveryEndpoint.java`
- Create: `src/main/java/com/lifepilot/llm/profile/MultiTurnHistoryRules.java`

- [ ] **Step 1：创建 ModelDiscoveryEndpoint**

```java
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
```

- [ ] **Step 2：创建 MultiTurnHistoryRules**

```java
package com.lifepilot.llm.profile;

/**
 * 多轮 history 注入规则 — 由 ChatHistoryAssembler 按规则序列化 history。
 *
 * @param injectReasoning           是否回传 reasoning_content
 * @param injectReasoningSignature  是否回传 reasoning_signature（仅 Anthropic）
 * @param injectToolCalls           是否回传 tool_calls 元数据
 * @param format                    reasoning 注入格式
 * @author zsg
 * @since 2026-04-27
 */
public record MultiTurnHistoryRules(
        boolean injectReasoning,
        boolean injectReasoningSignature,
        boolean injectToolCalls,
        ReasoningInjectionFormat format
) {
    /** 不回传任何 reasoning，标准多轮（OpenAI 官方 / 非推理模型） */
    public static MultiTurnHistoryRules standard() {
        return new MultiTurnHistoryRules(false, false, true, ReasoningInjectionFormat.CONTENT_ONLY);
    }

    /** DeepSeek / Qwen / 火山等：回传 reasoning_content，content 平级注入 */
    public static MultiTurnHistoryRules contentOnlyReasoning() {
        return new MultiTurnHistoryRules(true, false, true, ReasoningInjectionFormat.CONTENT_ONLY);
    }

    /** Anthropic：回传 thinking block + signature */
    public static MultiTurnHistoryRules anthropicThinkingBlock() {
        return new MultiTurnHistoryRules(true, true, true, ReasoningInjectionFormat.THINKING_BLOCK);
    }
}
```

- [ ] **Step 3：编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

### Task 1.3：创建 ProviderProfile record

**Files:**
- Create: `src/main/java/com/lifepilot/llm/profile/ProviderProfile.java`

- [ ] **Step 1：创建 record**

```java
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
```

- [ ] **Step 2：编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

### Task 1.4：创建 BuiltinProviderProfiles 常量

**Files:**
- Create: `src/main/java/com/lifepilot/llm/profile/BuiltinProviderProfiles.java`

- [ ] **Step 1：创建常量类**

```java
package com.lifepilot.llm.profile;

import com.lifepilot.llm.config.ProviderCapability;

import java.util.List;
import java.util.Set;

/**
 * 内置 ProviderProfile 列表 — 项目随版本演进维护，不进 DB。
 *
 * <p>11 个内置 profile 覆盖主流 provider。自部署兼容服务（vLLM / LM Studio /
 * Xinference）由用户挑最接近的 profile + 改 baseUrl，不需单独 profile。
 *
 * @author zsg
 * @since 2026-04-27
 */
public final class BuiltinProviderProfiles {

    private BuiltinProviderProfiles() {
    }

    public static final ProviderProfile DEEPSEEK_OFFICIAL = new ProviderProfile(
            "deepseek-official",
            "DeepSeek 官方",
            BaseAdapterType.OPENAI_BASE,
            "https://api.deepseek.com",
            ThinkingProtocolId.DEEPSEEK,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT),
            MultiTurnHistoryRules.contentOnlyReasoning()
    );

    public static final ProviderProfile QWEN_DASHSCOPE = new ProviderProfile(
            "qwen-dashscope",
            "通义千问 (DashScope)",
            BaseAdapterType.OPENAI_BASE,
            "https://dashscope.aliyuncs.com/compatible-mode",
            ThinkingProtocolId.QWEN,
            StructuredOutputMode.JSON_SCHEMA,
            PromptCacheStrategyId.DASHSCOPE_EXPLICIT,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING),
            MultiTurnHistoryRules.contentOnlyReasoning()
    );

    public static final ProviderProfile OPENAI_OFFICIAL = new ProviderProfile(
            "openai-official",
            "OpenAI 官方",
            BaseAdapterType.OPENAI_BASE,
            "https://api.openai.com",
            ThinkingProtocolId.OPENAI_REASONING_EFFORT,
            StructuredOutputMode.JSON_SCHEMA,
            PromptCacheStrategyId.OPENAI_AUTO,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING,
                    ProviderCapability.VISION, ProviderCapability.NATIVE_AUDIO),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile ANTHROPIC_OFFICIAL = new ProviderProfile(
            "anthropic-official",
            "Anthropic 官方",
            BaseAdapterType.ANTHROPIC_BASE,
            "https://api.anthropic.com",
            ThinkingProtocolId.ANTHROPIC,
            StructuredOutputMode.JSON_SCHEMA,
            PromptCacheStrategyId.ANTHROPIC_EPHEMERAL,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.VISION),
            MultiTurnHistoryRules.anthropicThinkingBlock()
    );

    public static final ProviderProfile OLLAMA_LOCAL = new ProviderProfile(
            "ollama-local",
            "Ollama 本地",
            BaseAdapterType.OLLAMA,
            "http://localhost:11434",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.PROMPT_ONLY,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.ollama(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile TEI_LOCAL = new ProviderProfile(
            "tei-local",
            "HuggingFace TEI 本地",
            BaseAdapterType.TEI,
            "http://localhost:8080",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.PROMPT_ONLY,
            PromptCacheStrategyId.NOOP,
            new ModelDiscoveryEndpoint("/info", "X-Unused", "${apiKey}", "$.model_id", null),
            Set.of(ProviderCapability.EMBEDDING, ProviderCapability.RERANK),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile VOLCENGINE_ARK = new ProviderProfile(
            "volcengine-ark",
            "火山方舟 (Ark)",
            BaseAdapterType.OPENAI_BASE,
            "https://ark.cn-beijing.volces.com/api",
            ThinkingProtocolId.DEEPSEEK,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT),
            MultiTurnHistoryRules.contentOnlyReasoning()
    );

    public static final ProviderProfile ZHIPU_BIGMODEL = new ProviderProfile(
            "zhipu-bigmodel",
            "智谱清言 (BigModel)",
            BaseAdapterType.OPENAI_BASE,
            "https://open.bigmodel.cn/api/paas",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile MOONSHOT_KIMI = new ProviderProfile(
            "moonshot-kimi",
            "月之暗面 Kimi",
            BaseAdapterType.OPENAI_BASE,
            "https://api.moonshot.cn",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile MINIMAX_TEXT = new ProviderProfile(
            "minimax-text",
            "MiniMax",
            BaseAdapterType.OPENAI_BASE,
            "https://api.minimax.chat",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT),
            MultiTurnHistoryRules.standard()
    );

    public static final ProviderProfile SILICONFLOW = new ProviderProfile(
            "siliconflow",
            "硅基流动 (SiliconFlow)",
            BaseAdapterType.OPENAI_BASE,
            "https://api.siliconflow.cn",
            ThinkingProtocolId.NONE,
            StructuredOutputMode.JSON_OBJECT,
            PromptCacheStrategyId.NOOP,
            ModelDiscoveryEndpoint.openAiCompatible(),
            Set.of(ProviderCapability.CHAT, ProviderCapability.EMBEDDING, ProviderCapability.RERANK),
            MultiTurnHistoryRules.standard()
    );

    /**
     * 获取所有内置 profile。
     *
     * @return 不可变列表
     */
    public static List<ProviderProfile> all() {
        return List.of(
                DEEPSEEK_OFFICIAL,
                QWEN_DASHSCOPE,
                OPENAI_OFFICIAL,
                ANTHROPIC_OFFICIAL,
                OLLAMA_LOCAL,
                TEI_LOCAL,
                VOLCENGINE_ARK,
                ZHIPU_BIGMODEL,
                MOONSHOT_KIMI,
                MINIMAX_TEXT,
                SILICONFLOW
        );
    }
}
```

- [ ] **Step 2：编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

### Task 1.5：创建 ProviderProfileRegistry

**Files:**
- Create: `src/main/java/com/lifepilot/llm/profile/ProviderProfileRegistry.java`

- [ ] **Step 1：写失败测试**

**Files:** Test: `src/test/java/com/lifepilot/llm/profile/ProviderProfileRegistry_注册查询测试.java`

```java
package com.lifepilot.llm.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProviderProfileRegistry 注册与查询行为单测。
 *
 * @author zsg
 * @since 2026-04-27
 */
class ProviderProfileRegistry_注册查询测试 {

    private final ProviderProfileRegistry registry = new ProviderProfileRegistry();

    @Test
    void 启动时加载全部内置_profile() {
        registry.init();
        assertThat(registry.all()).hasSize(11);
    }

    @Test
    void 按_id_精确返回_profile() {
        registry.init();
        var profile = registry.get("deepseek-official");
        assertThat(profile.thinkingProtocol()).isEqualTo(ThinkingProtocolId.DEEPSEEK);
    }

    @Test
    void 未知_id_抛出_IllegalStateException() {
        registry.init();
        assertThatThrownBy(() -> registry.get("unknown-profile"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知的 ProviderProfile id");
    }
}
```

- [ ] **Step 2：运行测试，确认失败**

Run: `mvn test -Dtest=ProviderProfileRegistry_注册查询测试 -q`
Expected: FAIL（ProviderProfileRegistry 类不存在）

- [ ] **Step 3：实现 Registry**

```java
package com.lifepilot.llm.profile;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内置 ProviderProfile 注册表。
 *
 * <p>启动时一次性加载 BuiltinProviderProfiles 列表到只读 Map，按 id 查询。
 *
 * @author zsg
 * @since 2026-04-27
 */
@Component
public class ProviderProfileRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderProfileRegistry.class);

    private Map<String, ProviderProfile> profilesById = Map.of();

    @PostConstruct
    public void init() {
        profilesById = BuiltinProviderProfiles.all().stream()
                .collect(Collectors.toUnmodifiableMap(
                        ProviderProfile::id, p -> p));
        log.info("ProviderProfileRegistry 加载完成: {} 个 profile", profilesById.size());
    }

    /**
     * 按 id 获取 profile，未知 id 抛 IllegalStateException。
     *
     * @param id Profile id
     * @return ProviderProfile
     */
    public ProviderProfile get(String id) {
        var profile = profilesById.get(id);
        if (profile == null) {
            throw new IllegalStateException("未知的 ProviderProfile id: " + id);
        }
        return profile;
    }

    /**
     * 列出所有 profile。
     *
     * @return 不可变列表
     */
    public List<ProviderProfile> all() {
        return List.copyOf(profilesById.values());
    }
}
```

- [ ] **Step 4：运行测试，确认通过**

Run: `mvn test -Dtest=ProviderProfileRegistry_注册查询测试 -q`
Expected: PASS（3 tests）

- [ ] **Step 5：commit Phase 1**

```bash
git add src/main/java/com/lifepilot/llm/profile/ src/test/java/com/lifepilot/llm/profile/
git commit -m "$(cat <<'EOF'
feat(llm): 引入 ProviderProfile 数据驱动

新增 com.lifepilot.llm.profile 包：
- 5 个枚举：BaseAdapterType / ThinkingProtocolId / StructuredOutputMode / PromptCacheStrategyId / ReasoningInjectionFormat
- 2 个 record：ModelDiscoveryEndpoint / MultiTurnHistoryRules / ProviderProfile
- BuiltinProviderProfiles 常量列表（11 个内置 profile）
- ProviderProfileRegistry Spring bean

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2：ThinkingProtocol 协议层（commit 2）

**目标**：5 个 ThinkingProtocol 实现承载 thinking 协议差异，每个实现独立可测。

### Task 2.1：创建 ThinkingMode 枚举 + RequestBuilder

**Files:**
- Create: `src/main/java/com/lifepilot/llm/thinking/ThinkingMode.java`
- Create: `src/main/java/com/lifepilot/llm/thinking/RequestBuilder.java`

- [ ] **Step 1：创建 ThinkingMode**

```java
package com.lifepilot.llm.thinking;

/**
 * 用户在 model_service 上配置的思考模式。
 *
 * @author zsg
 * @since 2026-04-27
 */
public enum ThinkingMode {
    /** 不下发 thinking 字段，使用 provider 默认行为 */
    AUTO,
    /** 显式开启思考 */
    ENABLED,
    /** 显式关闭思考 */
    DISABLED;

    public static ThinkingMode fromString(String s) {
        if (s == null || s.isBlank()) return AUTO;
        return switch (s.trim().toLowerCase()) {
            case "auto" -> AUTO;
            case "enabled", "on", "true" -> ENABLED;
            case "disabled", "off", "false" -> DISABLED;
            default -> throw new IllegalArgumentException("无效的 thinking_mode: " + s);
        };
    }
}
```

- [ ] **Step 2：创建 RequestBuilder**

```java
package com.lifepilot.llm.thinking;

import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 适配器侧可变请求构造抽象。
 *
 * <p>ThinkingProtocol 实现按需写入合适分支：
 * <ul>
 *   <li>原生字段（OpenAI reasoning.effort / Anthropic thinking）→ 通过 ChatOptions builder API
 *       由 Adapter 在构造 ChatOptions 时合并 chatOptionsExtras 字段；</li>
 *   <li>私有字段（DeepSeek extra_body / Qwen chat_template_kwargs）→ 塞进 extraBodyFields，
 *       由 Adapter 通过 RestClient 拦截器（复用 AbstractJsonBodyRewritingStrategy 模式）
 *       在出站 HTTP 请求 JSON body 合并。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
public class RequestBuilder {

    /** ChatOptions 原生字段扩展（Adapter 构造 ChatOptions 时读取） */
    private final Map<String, Object> chatOptionsExtras = new HashMap<>();

    /** 厂商私有 extra_body / chat_template_kwargs 字段（HTTP 拦截器读取并合并到 JSON body） */
    private final Map<String, Object> extraBodyFields = new HashMap<>();

    public void putChatOption(String key, Object value) {
        chatOptionsExtras.put(key, value);
    }

    public void putExtraBody(String key, Object value) {
        extraBodyFields.put(key, value);
    }

    public Map<String, Object> chatOptionsExtras() {
        return Map.copyOf(chatOptionsExtras);
    }

    public Map<String, Object> extraBodyFields() {
        return Map.copyOf(extraBodyFields);
    }

    @Nullable
    public Object getChatOption(String key) {
        return chatOptionsExtras.get(key);
    }
}
```

- [ ] **Step 3：编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

### Task 2.2：创建 ThinkingProtocol 接口

**Files:**
- Create: `src/main/java/com/lifepilot/llm/thinking/ThinkingProtocol.java`

- [ ] **Step 1：创建接口**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 推理模式协议处理器。
 *
 * <p>每个实现承载一家 provider 的 thinking 协议差异，通过三个方法表达：
 * <ol>
 *   <li>{@link #applyToRequest(RequestBuilder, ThinkingMode)} — 把 mode 转换成厂商私有字段；</li>
 *   <li>{@link #extractReasoning(JsonNode)} — 从响应（同步或流式 chunk）提取 reasoning_content；</li>
 *   <li>{@link #injectHistoryReasoning(AssistantMessageBuilder, Map)} — 多轮回传时按协议注入 reasoning。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-27
 */
public interface ThinkingProtocol {

    ThinkingProtocolId id();

    /**
     * 把 thinking_mode 转换成厂商私有字段。
     *
     * @param builder 可变请求构造器
     * @param mode    用户配置的 thinking_mode
     */
    void applyToRequest(RequestBuilder builder, ThinkingMode mode);

    /**
     * 从响应 JSON 提取 reasoning_content。
     *
     * @param rawResponseChunk 完整响应或单个流式 chunk 的 JSON 树
     * @return reasoning 文本，无则 null
     */
    @Nullable
    String extractReasoning(JsonNode rawResponseChunk);

    /**
     * 从响应 JSON 提取 signature（仅 Anthropic 使用）。
     *
     * @param rawResponseChunk 响应 JSON
     * @return signature，无则 null
     */
    @Nullable
    default String extractReasoningSignature(JsonNode rawResponseChunk) {
        return null;
    }

    /**
     * 多轮回传：上一轮 assistant 的 reasoning 注入到 history message。
     *
     * @param builder      assistant 消息构造器
     * @param prevPayload  上一轮 assistant 的 payload_json 反序列化结果
     */
    void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload);
}
```

- [ ] **Step 2：编译验证**

Run: `mvn compile -q`
Expected: BUILD FAIL — `AssistantMessageBuilder` 不存在

- [ ] **Step 3：先创建 AssistantMessageBuilder 占位骨架（完整实现在 Phase 5）**

**File:** Create: `src/main/java/com/lifepilot/llm/history/AssistantMessageBuilder.java`

```java
package com.lifepilot.llm.history;

import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Assistant 消息构造器 — 用于多轮 history 注入时构造 ProviderMessage。
 *
 * <p>骨架版本，Phase 5 ChatHistoryAssembler 实现时扩展。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class AssistantMessageBuilder {

    @Nullable private String content;
    @Nullable private String reasoningContent;
    @Nullable private String reasoningSignature;
    private final Map<String, Object> extras = new HashMap<>();

    public AssistantMessageBuilder content(String content) {
        this.content = content;
        return this;
    }

    public AssistantMessageBuilder reasoningContent(@Nullable String reasoningContent) {
        this.reasoningContent = reasoningContent;
        return this;
    }

    public AssistantMessageBuilder reasoningSignature(@Nullable String signature) {
        this.reasoningSignature = signature;
        return this;
    }

    public AssistantMessageBuilder putExtra(String key, Object value) {
        extras.put(key, value);
        return this;
    }

    @Nullable public String content() { return content; }
    @Nullable public String reasoningContent() { return reasoningContent; }
    @Nullable public String reasoningSignature() { return reasoningSignature; }
    public Map<String, Object> extras() { return Map.copyOf(extras); }
}
```

- [ ] **Step 4：编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

### Task 2.3：实现 NoopThinkingProtocol

**Files:**
- Create: `src/main/java/com/lifepilot/llm/thinking/NoopThinkingProtocol.java`
- Test: `src/test/java/com/lifepilot/llm/thinking/NoopThinkingProtocol_行为测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NoopThinkingProtocol_行为测试 {

    private final NoopThinkingProtocol protocol = new NoopThinkingProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_NONE() {
        assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.NONE);
    }

    @Test
    void applyToRequest_不写任何字段() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.chatOptionsExtras()).isEmpty();
        assertThat(builder.extraBodyFields()).isEmpty();
    }

    @Test
    void extractReasoning_始终返回_null() throws Exception {
        var node = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        assertThat(protocol.extractReasoning(node)).isNull();
    }

    @Test
    void injectHistoryReasoning_不修改_builder() {
        var builder = new AssistantMessageBuilder().content("hi");
        protocol.injectHistoryReasoning(builder, Map.of("reasoning_content", "应被忽略"));
        assertThat(builder.reasoningContent()).isNull();
    }
}
```

- [ ] **Step 2：运行失败**

Run: `mvn test -Dtest=NoopThinkingProtocol_行为测试 -q`
Expected: FAIL（NoopThinkingProtocol 不存在）

- [ ] **Step 3：实现**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 非推理模型的 noop 协议 — 所有操作都不做。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class NoopThinkingProtocol implements ThinkingProtocol {
    @Override public ThinkingProtocolId id() { return ThinkingProtocolId.NONE; }
    @Override public void applyToRequest(RequestBuilder builder, ThinkingMode mode) { }
    @Nullable @Override public String extractReasoning(JsonNode rawResponseChunk) { return null; }
    @Override public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) { }
}
```

- [ ] **Step 4：运行通过**

Run: `mvn test -Dtest=NoopThinkingProtocol_行为测试 -q`
Expected: PASS（4 tests）

### Task 2.4：实现 DeepSeekThinkingProtocol

**Files:**
- Create: `src/main/java/com/lifepilot/llm/thinking/DeepSeekThinkingProtocol.java`
- Test: `src/test/java/com/lifepilot/llm/thinking/DeepSeekThinkingProtocol_协议契约测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekThinkingProtocol_协议契约测试 {

    private final DeepSeekThinkingProtocol protocol = new DeepSeekThinkingProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_DEEPSEEK() {
        assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.DEEPSEEK);
    }

    @Test
    void mode_AUTO_不下发_thinking_字段() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.AUTO);
        assertThat(builder.extraBodyFields()).isEmpty();
    }

    @Test
    void mode_ENABLED_注入_thinking_enabled() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.extraBodyFields()).containsEntry("thinking", Map.of("type", "enabled"));
    }

    @Test
    void mode_DISABLED_注入_thinking_disabled() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.DISABLED);
        assertThat(builder.extraBodyFields()).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void 从同步响应_choices_message_reasoning_content_提取() throws Exception {
        var json = """
                {"choices":[{"message":{"content":"final","reasoning_content":"我先思考"}}]}
                """;
        var node = mapper.readTree(json);
        assertThat(protocol.extractReasoning(node)).isEqualTo("我先思考");
    }

    @Test
    void 从流式_chunk_choices_delta_reasoning_content_提取() throws Exception {
        var json = """
                {"choices":[{"delta":{"reasoning_content":"片段思考"}}]}
                """;
        var node = mapper.readTree(json);
        assertThat(protocol.extractReasoning(node)).isEqualTo("片段思考");
    }

    @Test
    void 响应不含_reasoning_content_返回_null() throws Exception {
        var node = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        assertThat(protocol.extractReasoning(node)).isNull();
    }

    @Test
    void injectHistoryReasoning_有_reasoning_时写入_builder() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of(
                "content", "final",
                "reasoning_content", "上一轮思考"
        ));
        assertThat(builder.reasoningContent()).isEqualTo("上一轮思考");
    }

    @Test
    void injectHistoryReasoning_缺_reasoning_时补_dummy_占位() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of("content", "final"));
        assertThat(builder.reasoningContent()).isEqualTo("");
    }
}
```

- [ ] **Step 2：运行失败**

Run: `mvn test -Dtest=DeepSeekThinkingProtocol_协议契约测试 -q`
Expected: FAIL

- [ ] **Step 3：实现**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * DeepSeek 推理模型协议（V4 系列）。
 *
 * <p>协议契约：
 * <ul>
 *   <li>请求：通过 extra_body.thinking.type = enabled/disabled 控制；AUTO 不下发；</li>
 *   <li>响应：reasoning_content 平级于 content，可能在 choices[].message 或 choices[].delta；</li>
 *   <li>多轮：assistant message 必须回传上一轮的 reasoning_content（缺则补 "" 占位）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
public class DeepSeekThinkingProtocol implements ThinkingProtocol {

    @Override
    public ThinkingProtocolId id() {
        return ThinkingProtocolId.DEEPSEEK;
    }

    @Override
    public void applyToRequest(RequestBuilder builder, ThinkingMode mode) {
        switch (mode) {
            case AUTO -> {
                // 不下发，使用 provider 默认（DeepSeek 默认 enabled）
            }
            case ENABLED -> builder.putExtraBody("thinking", Map.of("type", "enabled"));
            case DISABLED -> builder.putExtraBody("thinking", Map.of("type", "disabled"));
        }
    }

    @Nullable
    @Override
    public String extractReasoning(JsonNode rawResponseChunk) {
        if (rawResponseChunk == null) return null;
        var choices = rawResponseChunk.path("choices");
        if (!choices.isArray() || choices.size() == 0) return null;
        var first = choices.get(0);
        // 流式：delta.reasoning_content
        var deltaReasoning = first.path("delta").path("reasoning_content");
        if (deltaReasoning.isTextual()) {
            return deltaReasoning.asText();
        }
        // 同步：message.reasoning_content
        var messageReasoning = first.path("message").path("reasoning_content");
        if (messageReasoning.isTextual()) {
            return messageReasoning.asText();
        }
        return null;
    }

    @Override
    public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) {
        Object reasoning = prevPayload.get("reasoning_content");
        if (reasoning instanceof String s && !s.isEmpty()) {
            builder.reasoningContent(s);
        } else {
            // 缺 reasoning_content 时补 dummy 占位防止 DeepSeek 多轮 400
            builder.reasoningContent("");
        }
    }
}
```

- [ ] **Step 4：运行通过**

Run: `mvn test -Dtest=DeepSeekThinkingProtocol_协议契约测试 -q`
Expected: PASS（9 tests）

### Task 2.5：实现 QwenThinkingProtocol

**Files:**
- Create: `src/main/java/com/lifepilot/llm/thinking/QwenThinkingProtocol.java`
- Test: `src/test/java/com/lifepilot/llm/thinking/QwenThinkingProtocol_协议契约测试.java`

- [ ] **Step 1：写失败测试（关键差异行）**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QwenThinkingProtocol_协议契约测试 {

    private final QwenThinkingProtocol protocol = new QwenThinkingProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_QWEN() { assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.QWEN); }

    @Test
    void mode_ENABLED_注入_chat_template_kwargs_enable_thinking_true() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.extraBodyFields())
                .containsEntry("chat_template_kwargs", Map.of("enable_thinking", true));
    }

    @Test
    void mode_DISABLED_注入_chat_template_kwargs_enable_thinking_false() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.DISABLED);
        assertThat(builder.extraBodyFields())
                .containsEntry("chat_template_kwargs", Map.of("enable_thinking", false));
    }

    @Test
    void mode_AUTO_不下发() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.AUTO);
        assertThat(builder.extraBodyFields()).isEmpty();
    }

    @Test
    void 提取_reasoning_content_与_DeepSeek_位置一致() throws Exception {
        var node = mapper.readTree("{\"choices\":[{\"message\":{\"reasoning_content\":\"qwen 思考\"}}]}");
        assertThat(protocol.extractReasoning(node)).isEqualTo("qwen 思考");
    }

    @Test
    void injectHistoryReasoning_保守策略_缺时补空字符串() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of("content", "final"));
        assertThat(builder.reasoningContent()).isEqualTo("");
    }
}
```

- [ ] **Step 2：运行失败**

Run: `mvn test -Dtest=QwenThinkingProtocol_协议契约测试 -q`
Expected: FAIL

- [ ] **Step 3：实现**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Qwen3 推理模型协议（DashScope）。
 *
 * <p>协议契约：
 * <ul>
 *   <li>请求：通过 chat_template_kwargs.enable_thinking 控制；</li>
 *   <li>响应：reasoning_content 位置同 DeepSeek；</li>
 *   <li>多轮：保守策略默认回传 reasoning_content，避免 Qwen 后续版本变契约导致 400。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
public class QwenThinkingProtocol implements ThinkingProtocol {

    @Override
    public ThinkingProtocolId id() {
        return ThinkingProtocolId.QWEN;
    }

    @Override
    public void applyToRequest(RequestBuilder builder, ThinkingMode mode) {
        switch (mode) {
            case AUTO -> { /* 不下发 */ }
            case ENABLED -> builder.putExtraBody("chat_template_kwargs",
                    Map.of("enable_thinking", true));
            case DISABLED -> builder.putExtraBody("chat_template_kwargs",
                    Map.of("enable_thinking", false));
        }
    }

    @Nullable
    @Override
    public String extractReasoning(JsonNode rawResponseChunk) {
        if (rawResponseChunk == null) return null;
        var choices = rawResponseChunk.path("choices");
        if (!choices.isArray() || choices.size() == 0) return null;
        var first = choices.get(0);
        var deltaReasoning = first.path("delta").path("reasoning_content");
        if (deltaReasoning.isTextual()) return deltaReasoning.asText();
        var messageReasoning = first.path("message").path("reasoning_content");
        if (messageReasoning.isTextual()) return messageReasoning.asText();
        return null;
    }

    @Override
    public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) {
        Object reasoning = prevPayload.get("reasoning_content");
        if (reasoning instanceof String s && !s.isEmpty()) {
            builder.reasoningContent(s);
        } else {
            builder.reasoningContent("");
        }
    }
}
```

- [ ] **Step 4：运行通过**

Run: `mvn test -Dtest=QwenThinkingProtocol_协议契约测试 -q`
Expected: PASS（6 tests）

### Task 2.6：实现 OpenAiReasoningEffortProtocol

**Files:**
- Create: `src/main/java/com/lifepilot/llm/thinking/OpenAiReasoningEffortProtocol.java`
- Test: `src/test/java/com/lifepilot/llm/thinking/OpenAiReasoningEffortProtocol_协议契约测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiReasoningEffortProtocol_协议契约测试 {

    private final OpenAiReasoningEffortProtocol protocol = new OpenAiReasoningEffortProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_OPENAI_REASONING_EFFORT() {
        assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.OPENAI_REASONING_EFFORT);
    }

    @Test
    void mode_ENABLED_写_chatOption_reasoning_effort_medium() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.chatOptionsExtras()).containsEntry("reasoning_effort", "medium");
    }

    @Test
    void mode_DISABLED_写_reasoning_effort_none() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.DISABLED);
        assertThat(builder.chatOptionsExtras()).containsEntry("reasoning_effort", "none");
    }

    @Test
    void mode_AUTO_不下发() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.AUTO);
        assertThat(builder.chatOptionsExtras()).isEmpty();
    }

    @Test
    void extractReasoning_始终返回_null() throws Exception {
        var node = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        assertThat(protocol.extractReasoning(node)).isNull();
    }

    @Test
    void injectHistoryReasoning_不操作() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of("reasoning_content", "应被忽略"));
        assertThat(builder.reasoningContent()).isNull();
    }
}
```

- [ ] **Step 2：运行失败**

Run: `mvn test -Dtest=OpenAiReasoningEffortProtocol_协议契约测试 -q`
Expected: FAIL

- [ ] **Step 3：实现**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * OpenAI o1 / o3 / GPT-5 系列协议。
 *
 * <p>协议契约：
 * <ul>
 *   <li>请求：reasoning.effort 参数（none/minimal/low/medium/high）；OpenAI SDK 通过 ChatOptions 暴露；</li>
 *   <li>响应：API 不返回 reasoning，仅返回 content（如需 reasoning 摘要要单独配置 reasoning summary）；</li>
 *   <li>多轮：无需回传 reasoning。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
public class OpenAiReasoningEffortProtocol implements ThinkingProtocol {

    @Override
    public ThinkingProtocolId id() {
        return ThinkingProtocolId.OPENAI_REASONING_EFFORT;
    }

    @Override
    public void applyToRequest(RequestBuilder builder, ThinkingMode mode) {
        switch (mode) {
            case AUTO -> { /* 不下发 */ }
            case ENABLED -> builder.putChatOption("reasoning_effort", "medium");
            case DISABLED -> builder.putChatOption("reasoning_effort", "none");
        }
    }

    @Nullable
    @Override
    public String extractReasoning(JsonNode rawResponseChunk) {
        return null;
    }

    @Override
    public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) {
        // OpenAI 不需要回传 reasoning
    }
}
```

- [ ] **Step 4：运行通过**

Run: `mvn test -Dtest=OpenAiReasoningEffortProtocol_协议契约测试 -q`
Expected: PASS（6 tests）

### Task 2.7：实现 AnthropicThinkingProtocol

**Files:**
- Create: `src/main/java/com/lifepilot/llm/thinking/AnthropicThinkingProtocol.java`
- Test: `src/test/java/com/lifepilot/llm/thinking/AnthropicThinkingProtocol_协议契约测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicThinkingProtocol_协议契约测试 {

    private final AnthropicThinkingProtocol protocol = new AnthropicThinkingProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void id_返回_ANTHROPIC() { assertThat(protocol.id()).isEqualTo(ThinkingProtocolId.ANTHROPIC); }

    @Test
    void mode_ENABLED_写_thinking_对象_含_budget_tokens() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);
        assertThat(builder.chatOptionsExtras()).containsKey("thinking");
        var thinking = (Map<?, ?>) builder.getChatOption("thinking");
        assertThat(thinking).containsEntry("type", "enabled");
        assertThat(thinking).containsKey("budget_tokens");
    }

    @Test
    void mode_DISABLED_写_thinking_disabled() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.DISABLED);
        var thinking = (Map<?, ?>) builder.getChatOption("thinking");
        assertThat(thinking).containsEntry("type", "disabled");
    }

    @Test
    void mode_AUTO_写_thinking_adaptive() {
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.AUTO);
        var thinking = (Map<?, ?>) builder.getChatOption("thinking");
        assertThat(thinking).containsEntry("type", "adaptive");
    }

    @Test
    void 从_content_array_thinking_block_提取_thinking() throws Exception {
        var json = """
                {"content":[
                  {"type":"thinking","thinking":"我先想想","signature":"sig123"},
                  {"type":"text","text":"final answer"}
                ]}
                """;
        var node = mapper.readTree(json);
        assertThat(protocol.extractReasoning(node)).isEqualTo("我先想想");
        assertThat(protocol.extractReasoningSignature(node)).isEqualTo("sig123");
    }

    @Test
    void 流式_chunk_的_content_block_delta_thinking_提取() throws Exception {
        var json = """
                {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"片段"}}
                """;
        var node = mapper.readTree(json);
        assertThat(protocol.extractReasoning(node)).isEqualTo("片段");
    }

    @Test
    void injectHistoryReasoning_写入_thinking_block_格式_占位标记() {
        var builder = new AssistantMessageBuilder().content("final");
        protocol.injectHistoryReasoning(builder, Map.of(
                "content", "final",
                "reasoning_content", "上一轮思考",
                "reasoning_signature", "sig"
        ));
        assertThat(builder.reasoningContent()).isEqualTo("上一轮思考");
        assertThat(builder.reasoningSignature()).isEqualTo("sig");
    }
}
```

- [ ] **Step 2：运行失败**

Run: `mvn test -Dtest=AnthropicThinkingProtocol_协议契约测试 -q`
Expected: FAIL

- [ ] **Step 3：实现**

```java
package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Anthropic Claude 4.x Extended Thinking 协议。
 *
 * <p>协议契约：
 * <ul>
 *   <li>请求：thinking.type = enabled/disabled/adaptive；ENABLED 带 budget_tokens；
 *       Claude 4.7 仅支持 adaptive，旧版降级（本协议默认 adaptive）；</li>
 *   <li>响应：content array 中 type=="thinking" 块包含 thinking + signature 两字段；
 *       流式时为 content_block_delta 事件中 delta.type=="thinking_delta"；</li>
 *   <li>多轮：thinking block 整体回传到 content array 第一块（含 signature）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
public class AnthropicThinkingProtocol implements ThinkingProtocol {

    private static final int DEFAULT_BUDGET_TOKENS = 8192;

    @Override
    public ThinkingProtocolId id() {
        return ThinkingProtocolId.ANTHROPIC;
    }

    @Override
    public void applyToRequest(RequestBuilder builder, ThinkingMode mode) {
        switch (mode) {
            case AUTO -> builder.putChatOption("thinking", Map.of("type", "adaptive"));
            case ENABLED -> builder.putChatOption("thinking",
                    Map.of("type", "enabled", "budget_tokens", DEFAULT_BUDGET_TOKENS));
            case DISABLED -> builder.putChatOption("thinking", Map.of("type", "disabled"));
        }
    }

    @Nullable
    @Override
    public String extractReasoning(JsonNode rawResponseChunk) {
        if (rawResponseChunk == null) return null;
        // 流式：content_block_delta + delta.type==thinking_delta
        var deltaType = rawResponseChunk.path("delta").path("type").asText("");
        if ("thinking_delta".equals(deltaType)) {
            var thinking = rawResponseChunk.path("delta").path("thinking");
            if (thinking.isTextual()) return thinking.asText();
        }
        // 同步：content array 找 type==thinking 的块
        var content = rawResponseChunk.path("content");
        if (content.isArray()) {
            for (var block : content) {
                if ("thinking".equals(block.path("type").asText(""))) {
                    var thinking = block.path("thinking");
                    if (thinking.isTextual()) return thinking.asText();
                }
            }
        }
        return null;
    }

    @Nullable
    @Override
    public String extractReasoningSignature(JsonNode rawResponseChunk) {
        if (rawResponseChunk == null) return null;
        var content = rawResponseChunk.path("content");
        if (content.isArray()) {
            for (var block : content) {
                if ("thinking".equals(block.path("type").asText(""))) {
                    var sig = block.path("signature");
                    if (sig.isTextual()) return sig.asText();
                }
            }
        }
        return null;
    }

    @Override
    public void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload) {
        Object reasoning = prevPayload.get("reasoning_content");
        Object signature = prevPayload.get("reasoning_signature");
        if (reasoning instanceof String r && !r.isEmpty()) {
            builder.reasoningContent(r);
            if (signature instanceof String s && !s.isEmpty()) {
                builder.reasoningSignature(s);
            }
        }
    }
}
```

- [ ] **Step 4：运行通过**

Run: `mvn test -Dtest=AnthropicThinkingProtocol_协议契约测试 -q`
Expected: PASS（7 tests）

- [ ] **Step 5：commit Phase 2**

```bash
git add src/main/java/com/lifepilot/llm/thinking/ src/main/java/com/lifepilot/llm/history/AssistantMessageBuilder.java src/test/java/com/lifepilot/llm/thinking/
git commit -m "$(cat <<'EOF'
feat(llm): ThinkingProtocol 接口 + 5 实现

新增 com.lifepilot.llm.thinking 包：
- ThinkingMode / RequestBuilder
- ThinkingProtocol 接口
- 5 实现：Noop / DeepSeek / Qwen / OpenAiReasoningEffort / Anthropic
- 每实现独立 contract test（覆盖 applyToRequest / extractReasoning / injectHistoryReasoning）

预先创建 AssistantMessageBuilder 骨架（Phase 5 ChatHistoryAssembler 扩展）。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3：Adapter 多态拆分（commit 3）

**目标**：把 `SpringAiProviderAdapter` 单类拆成基类 + OpenAiBase + 5 具体子类，按 `ProviderProfile.baseAdapter + thinkingProtocol` 路由。**保持现有 LlmResponse 旧字段签名**，确保 Phase 4 之前现有测试不破。

### Task 3.1：创建 ToolCall record（公共类型）

**Files:**
- Create: `src/main/java/com/lifepilot/llm/ToolCall.java`

- [ ] **Step 1：创建 record**

```java
package com.lifepilot.llm;

/**
 * LLM tool call 元数据。
 *
 * @param id           tool call ID
 * @param name         工具名
 * @param argumentsJson JSON 字符串形式的参数
 * @author zsg
 * @since 2026-04-27
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
```

- [ ] **Step 2：编译验证**

Run: `mvn compile -q`
Expected: SUCCESS

### Task 3.2：扩展 ProviderAdapter 接口（permits 改）

**Files:**
- Modify: `src/main/java/com/lifepilot/llm/adapter/ProviderAdapter.java`

- [ ] **Step 1：把 sealed permits 改成允许新子类**

```java
// 修改 ProviderAdapter.java 第 21 行
public sealed interface ProviderAdapter permits AbstractProviderAdapter {
```

- [ ] **Step 2：先创建空 AbstractProviderAdapter 占位类**

**File:** Create: `src/main/java/com/lifepilot/llm/adapter/AbstractProviderAdapter.java`

```java
package com.lifepilot.llm.adapter;

import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.multimodal.MediaContent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Provider 适配器抽象基类 — 占位骨架，Task 3.3 实现完整逻辑。
 *
 * @author zsg
 * @since 2026-04-27
 */
public abstract non-sealed class AbstractProviderAdapter implements ProviderAdapter {
    // 实现在 Task 3.3
    @Override public LlmResponse call(String prompt, @Nullable String outputSchema, Duration timeout) { throw new UnsupportedOperationException("待实现"); }
    @Override public <T> T callEntity(String prompt, Class<T> responseType) { throw new UnsupportedOperationException("待实现"); }
    @Override public float[] embed(String text) { throw new UnsupportedOperationException("待实现"); }
    @Override public Flux<String> stream(String prompt) { throw new UnsupportedOperationException("待实现"); }
    @Override public Optional<ChatClient> chatClient() { return Optional.empty(); }
    @Override public boolean healthCheck() { return false; }
    @Override public LlmResponse callWithMedia(String p, List<MediaContent> m, @Nullable String s, Duration t) { throw new UnsupportedOperationException("待实现"); }
    @Override public Flux<String> streamWithMedia(String p, List<MediaContent> m) { throw new UnsupportedOperationException("待实现"); }
    @Override public LlmResponse callWithVideo(String t, String u, @Nullable String s, Duration to) { throw new UnsupportedOperationException("待实现"); }
    @Override public LlmResponse callWithAudio(String p, List<MediaContent> a, @Nullable String s, Duration t) { throw new UnsupportedOperationException("待实现"); }
    @Override public Flux<String> streamWithAudio(String p, List<MediaContent> a) { throw new UnsupportedOperationException("待实现"); }
}
```

- [ ] **Step 3：编译验证**

Run: `mvn compile -q`
Expected: BUILD FAIL — 旧 `SpringAiProviderAdapter` 不再被允许（permits 改了）

### Task 3.3：把 SpringAiProviderAdapter 内容迁移到 AbstractProviderAdapter

**Files:**
- Modify: `src/main/java/com/lifepilot/llm/adapter/AbstractProviderAdapter.java`
- Delete: `src/main/java/com/lifepilot/llm/adapter/SpringAiProviderAdapter.java`（迁移完成后）

- [ ] **Step 1：把 SpringAiProviderAdapter 的字段、构造器、所有方法迁移到 AbstractProviderAdapter**

把 `SpringAiProviderAdapter.java` 第 49-510 行的内容（除了 class 声明）整体粘贴到 `AbstractProviderAdapter.java`，替换占位实现。
关键调整：
- class 名改为 `AbstractProviderAdapter`
- 改成 `public abstract non-sealed class`
- `private final ProviderConfig config` → `protected final ProviderConfig config`
- `private final ChatModel chatModel` → `protected final ChatModel chatModel`
- 所有 `private` helper 方法改为 `protected`
- 保留方法实现完全不变（call / callEntity / embed / stream / 等）

- [ ] **Step 2：删除 SpringAiProviderAdapter.java**

```bash
git rm src/main/java/com/lifepilot/llm/adapter/SpringAiProviderAdapter.java
```

- [ ] **Step 3：在 ProviderRegistry.java 把 SpringAiProviderAdapter 引用改为 AbstractProviderAdapter**

修改 `src/main/java/com/lifepilot/llm/registry/ProviderRegistry.java`：
- 第 5 行 `import com.lifepilot.llm.adapter.SpringAiProviderAdapter;` 删除
- 第 28 行 `private final ConcurrentHashMap<String, SpringAiProviderAdapter> adapters` → `private final ConcurrentHashMap<String, AbstractProviderAdapter> adapters`
- ProviderAdapterFactory.create 返回类型同步改

- [ ] **Step 4：编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5：跑现有测试，无回归**

Run: `mvn test -q`
Expected: 全部测试 PASS

### Task 3.4：创建 OpenAiBaseProviderAdapter

**Files:**
- Create: `src/main/java/com/lifepilot/llm/adapter/OpenAiBaseProviderAdapter.java`

- [ ] **Step 1：写实现**

```java
package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.profile.ProviderProfile;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * OpenAI 兼容 Adapter 基类 — 共享 OpenAI SDK 路径。
 *
 * <p>承载 DeepSeek / Qwen / OpenAI 官方 / 智谱 / 月之暗面 / 火山等 provider 的共同行为。
 * 子类按需重写 ChatOptions 构造、特殊字段注入、协议私有错误码处理。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class OpenAiBaseProviderAdapter extends AbstractProviderAdapter {

    protected final ProviderProfile profile;
    protected final ThinkingProtocol thinkingProtocol;

    public OpenAiBaseProviderAdapter(ProviderConfig config,
                                     ChatModel chatModel,
                                     @Nullable EmbeddingModel embeddingModel,
                                     @Nullable List<CallAdvisor> defaultAdvisors,
                                     ProviderProfile profile,
                                     ThinkingProtocol thinkingProtocol) {
        super(config, chatModel, embeddingModel, defaultAdvisors);
        this.profile = profile;
        this.thinkingProtocol = thinkingProtocol;
    }

    public ProviderProfile profile() {
        return profile;
    }

    public ThinkingProtocol thinkingProtocol() {
        return thinkingProtocol;
    }
}
```

注意：`AbstractProviderAdapter` 的构造器需要从 SpringAiProviderAdapter 迁移过来时同步开放为 `protected`。如尚未开放，先调整。

- [ ] **Step 2：编译验证**

Run: `mvn compile -q`
Expected: SUCCESS

### Task 3.5：创建 5 个具体 Adapter 子类（DeepSeek / Qwen / OpenAi / Anthropic / Ollama）

**Files:** 5 个新类（每个仅 20-30 行）

- [ ] **Step 1：创建 DeepSeekProviderAdapter**

```java
package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.profile.ProviderProfile;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * DeepSeek 适配器 — 复用 OpenAI SDK 路径，thinking 字段走 extra_body。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class DeepSeekProviderAdapter extends OpenAiBaseProviderAdapter {
    public DeepSeekProviderAdapter(ProviderConfig config, ChatModel chatModel,
                                   @Nullable EmbeddingModel embeddingModel,
                                   @Nullable List<CallAdvisor> defaultAdvisors,
                                   ProviderProfile profile, ThinkingProtocol thinkingProtocol) {
        super(config, chatModel, embeddingModel, defaultAdvisors, profile, thinkingProtocol);
    }
}
```

- [ ] **Step 2：创建 QwenProviderAdapter**（同 DeepSeek 结构）

```java
package com.lifepilot.llm.adapter;
// imports 同上

public class QwenProviderAdapter extends OpenAiBaseProviderAdapter {
    public QwenProviderAdapter(ProviderConfig config, ChatModel chatModel,
                               @Nullable EmbeddingModel embeddingModel,
                               @Nullable List<CallAdvisor> defaultAdvisors,
                               ProviderProfile profile, ThinkingProtocol thinkingProtocol) {
        super(config, chatModel, embeddingModel, defaultAdvisors, profile, thinkingProtocol);
    }
}
```

- [ ] **Step 3：创建 OpenAiOfficialProviderAdapter**（同结构）

```java
public class OpenAiOfficialProviderAdapter extends OpenAiBaseProviderAdapter { /* 构造器同上 */ }
```

- [ ] **Step 4：创建 AnthropicProviderAdapter**（继承 AbstractProviderAdapter，不走 OpenAI base）

```java
package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.profile.ProviderProfile;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Anthropic 原生适配器 — Spring AI AnthropicChatModel 路径 + thinking block 协议。
 *
 * @author zsg
 * @since 2026-04-27
 */
public class AnthropicProviderAdapter extends AbstractProviderAdapter {

    protected final ProviderProfile profile;
    protected final ThinkingProtocol thinkingProtocol;

    public AnthropicProviderAdapter(ProviderConfig config, ChatModel chatModel,
                                    @Nullable List<CallAdvisor> defaultAdvisors,
                                    ProviderProfile profile, ThinkingProtocol thinkingProtocol) {
        super(config, chatModel, null, defaultAdvisors);
        this.profile = profile;
        this.thinkingProtocol = thinkingProtocol;
    }

    public ProviderProfile profile() { return profile; }
    public ThinkingProtocol thinkingProtocol() { return thinkingProtocol; }
}
```

- [ ] **Step 5：创建 OllamaProviderAdapter**（同 Anthropic 结构，但带 EmbeddingModel）

```java
public class OllamaProviderAdapter extends AbstractProviderAdapter {
    protected final ProviderProfile profile;

    public OllamaProviderAdapter(ProviderConfig config, ChatModel chatModel,
                                 @Nullable EmbeddingModel embeddingModel,
                                 @Nullable List<CallAdvisor> defaultAdvisors,
                                 ProviderProfile profile) {
        super(config, chatModel, embeddingModel, defaultAdvisors);
        this.profile = profile;
    }

    public ProviderProfile profile() { return profile; }
}
```

- [ ] **Step 6：编译验证**

Run: `mvn compile -q`
Expected: SUCCESS

### Task 3.6：改写 ProviderAdapterFactory 按 profile 路由

**Files:**
- Modify: `src/main/java/com/lifepilot/llm/adapter/ProviderAdapterFactory.java`

- [ ] **Step 1：注入 ProfileRegistry + ThinkingProtocol map**

```java
// 在 ProviderAdapterFactory 类头部
private final ProviderProfileRegistry profileRegistry;
private final Map<ThinkingProtocolId, ThinkingProtocol> thinkingProtocols;

public ProviderAdapterFactory(@Nullable List<CallAdvisor> defaultAdvisors,
                              @Nullable ConnectionPoolConfigEntry connectionPoolConfig,
                              ProviderProfileRegistry profileRegistry,
                              List<ThinkingProtocol> thinkingProtocolImpls) {
    this.defaultAdvisors = defaultAdvisors != null ? List.copyOf(defaultAdvisors) : List.of();
    this.connectionPoolConfig = connectionPoolConfig;
    this.profileRegistry = profileRegistry;
    this.thinkingProtocols = thinkingProtocolImpls.stream()
            .collect(Collectors.toUnmodifiableMap(ThinkingProtocol::id, p -> p));
}
```

- [ ] **Step 2：改写 create() 方法**

```java
public AbstractProviderAdapter create(ProviderConfig config) {
    var profile = profileRegistry.get(config.profileId());
    var thinkingProtocol = thinkingProtocols.getOrDefault(
            profile.thinkingProtocol(),
            thinkingProtocols.get(ThinkingProtocolId.NONE));

    return switch (profile.baseAdapter()) {
        case OPENAI_BASE -> createOpenAiBaseAdapter(config, profile, thinkingProtocol);
        case ANTHROPIC_BASE -> createAnthropicAdapter(config, profile, thinkingProtocol);
        case OLLAMA -> createOllamaAdapterNew(config, profile);
        case TEI -> createOpenAiBaseAdapter(config, profile, thinkingProtocol);
    };
}

private OpenAiBaseProviderAdapter createOpenAiBaseAdapter(ProviderConfig config,
                                                          ProviderProfile profile,
                                                          ThinkingProtocol thinkingProtocol) {
    // 复用现有 createOpenAiCompatibleAdapter 的 ChatModel / EmbeddingModel 构造逻辑
    String baseUrl = normalizeOpenAiCompatibleBaseUrl(config.apiUrl(), config.id());
    var openAiApiBuilder = OpenAiApi.builder().baseUrl(baseUrl);
    if (config.apiKey() != null && !config.apiKey().isBlank()) {
        openAiApiBuilder.apiKey(config.apiKey());
    }
    PromptCacheStrategy cacheStrategy = PromptCacheStrategies.resolve(config);
    applyCacheStrategyToOpenAi(openAiApiBuilder, cacheStrategy, config);
    var openAiApi = openAiApiBuilder.build();
    var chatOptions = OpenAiChatOptions.builder().model(config.modelName()).build();
    ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi).defaultOptions(chatOptions).build();
    EmbeddingModel embeddingModel = null;
    if (config.hasCapability(ProviderCapability.EMBEDDING)) {
        embeddingModel = new OpenAiEmbeddingModel(openAiApi);
    }

    // 按 profile.id 选具体子类
    return switch (profile.id()) {
        case "deepseek-official", "volcengine-ark" ->
                new DeepSeekProviderAdapter(config, chatModel, embeddingModel,
                        defaultAdvisors, profile, thinkingProtocol);
        case "qwen-dashscope" ->
                new QwenProviderAdapter(config, chatModel, embeddingModel,
                        defaultAdvisors, profile, thinkingProtocol);
        case "openai-official" ->
                new OpenAiOfficialProviderAdapter(config, chatModel, embeddingModel,
                        defaultAdvisors, profile, thinkingProtocol);
        default -> // 智谱 / 月之暗面 / MiniMax / 硅基流动 / TEI 等
                new OpenAiBaseProviderAdapter(config, chatModel, embeddingModel,
                        defaultAdvisors, profile, thinkingProtocol);
    };
}

private AnthropicProviderAdapter createAnthropicAdapter(ProviderConfig config,
                                                        ProviderProfile profile,
                                                        ThinkingProtocol thinkingProtocol) {
    // 复用现有 createAnthropicAdapter 的 ChatModel 构造逻辑
    String baseUrl = config.apiUrl();
    if (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")) {
        baseUrl = baseUrl.replaceAll("/v1/?$", "");
    }
    var anthropicApi = AnthropicApi.builder()
            .apiKey(config.apiKey()).baseUrl(baseUrl).build();
    var chatOptions = AnthropicChatOptions.builder().model(config.modelName()).build();
    ChatModel chatModel = AnthropicChatModel.builder()
            .anthropicApi(anthropicApi).defaultOptions(chatOptions).build();
    return new AnthropicProviderAdapter(config, chatModel, defaultAdvisors,
            profile, thinkingProtocol);
}

private OllamaProviderAdapter createOllamaAdapterNew(ProviderConfig config,
                                                     ProviderProfile profile) {
    var ollamaApi = OllamaApi.builder().baseUrl(config.apiUrl()).build();
    var chatOptions = OllamaChatOptions.builder().model(config.modelName()).build();
    ChatModel chatModel = OllamaChatModel.builder()
            .ollamaApi(ollamaApi).defaultOptions(chatOptions).build();
    EmbeddingModel embeddingModel = null;
    if (config.hasCapability(ProviderCapability.EMBEDDING)) {
        var embOpts = OllamaEmbeddingOptions.builder().model(config.modelName()).build();
        embeddingModel = OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi).defaultOptions(embOpts).build();
    }
    return new OllamaProviderAdapter(config, chatModel, embeddingModel, defaultAdvisors, profile);
}
```

- [ ] **Step 3：删除旧的 createOllamaAdapter / createAnthropicAdapter / createOpenAiCompatibleAdapter 方法**

- [ ] **Step 4：在 LlmAutoConfiguration 把 5 个 ThinkingProtocol bean + ProfileRegistry 注入到 ProviderAdapterFactory**

修改 `src/main/java/com/lifepilot/llm/config/LlmAutoConfiguration.java`：

```java
@Bean
public NoopThinkingProtocol noopThinkingProtocol() { return new NoopThinkingProtocol(); }

@Bean
public DeepSeekThinkingProtocol deepSeekThinkingProtocol() { return new DeepSeekThinkingProtocol(); }

@Bean
public QwenThinkingProtocol qwenThinkingProtocol() { return new QwenThinkingProtocol(); }

@Bean
public OpenAiReasoningEffortProtocol openAiReasoningEffortProtocol() { return new OpenAiReasoningEffortProtocol(); }

@Bean
public AnthropicThinkingProtocol anthropicThinkingProtocol() { return new AnthropicThinkingProtocol(); }

@Bean
public ProviderAdapterFactory providerAdapterFactory(
        @Autowired(required = false) List<CallAdvisor> defaultAdvisors,
        @Autowired(required = false) ConnectionPoolConfigEntry connectionPoolConfig,
        ProviderProfileRegistry profileRegistry,
        List<ThinkingProtocol> thinkingProtocols) {
    return new ProviderAdapterFactory(defaultAdvisors, connectionPoolConfig,
            profileRegistry, thinkingProtocols);
}
```

- [ ] **Step 5：编译验证**

Run: `mvn compile -q`
Expected: 可能 FAIL — `config.profileId()` 字段尚未添加（Phase 6 添加）

为了让 Phase 3 自洽，先临时给 ProviderConfig 加 profileId 字段（Phase 6 时正式纳入完整 schema）：

修改 `src/main/java/com/lifepilot/llm/config/ProviderConfig.java`，在 record 字段加 `String profileId`，紧凑构造器加 `Objects.requireNonNull(profileId, ...)`，构造方调用方一律传 profileId（暂时硬编码"openai-official"或类似默认值，Phase 6 由 ModelService 注入实际值）。

- [ ] **Step 6：编译验证**

Run: `mvn compile -q`
Expected: SUCCESS

- [ ] **Step 7：跑测试，无回归**

Run: `mvn test -q`
Expected: 全部 PASS

- [ ] **Step 8：commit Phase 3**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(llm): Adapter 多态拆分

把 SpringAiProviderAdapter 拆为 AbstractProviderAdapter + OpenAiBase +
5 具体子类（DeepSeek / Qwen / OpenAiOfficial / Anthropic / Ollama）。
ProviderAdapterFactory 改写为按 ProviderProfile.baseAdapter + thinkingProtocol 路由。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4：LlmResponse 富字段化 + Stream 事件流（commit 4）

**目标**：响应类型升级为富字段，流式从 `Flux<String>` 改为 `Flux<LlmStreamEvent>`，回调层（StreamingCallback / NonStreamingCallback）适配。

### Task 4.1：重写 LlmResponse

**Files:**
- Modify: `src/main/java/com/lifepilot/llm/LlmResponse.java`

- [ ] **Step 1：写新 LlmResponse**

```java
package com.lifepilot.llm;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 调用统一响应（富字段版本）。
 *
 * @author zsg
 * @since 2026-04-27
 */
public record LlmResponse(
        String content,
        @Nullable String reasoningContent,
        @Nullable String reasoningSignature,
        List<ToolCall> toolCalls,
        Map<String, Object> providerMetadata,
        int inputTokens,
        int outputTokens,
        @Nullable Integer reasoningTokens,
        int cachedInputTokens,
        String providerId,
        String modelName,
        long latencyMs,
        boolean cached
) {
    public LlmResponse {
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        providerMetadata = providerMetadata != null ? Map.copyOf(providerMetadata) : Map.of();
    }

    public int totalTokens() {
        return inputTokens + outputTokens + Optional.ofNullable(reasoningTokens).orElse(0);
    }

    public static LlmResponse cached(String content, String providerId, String modelName) {
        return new LlmResponse(content, null, null, List.of(), Map.of(),
                0, 0, null, 0, providerId, modelName, 0, true);
    }

    /** 兼容老调用：仅含 content + tokens 的简单构造（迁移期使用）。 */
    public static LlmResponse simple(String content, int inputTokens, int outputTokens,
                                     String providerId, String modelName, long latencyMs) {
        return new LlmResponse(content, null, null, List.of(), Map.of(),
                inputTokens, outputTokens, null, 0,
                providerId, modelName, latencyMs, false);
    }
}
```

- [ ] **Step 2：批量更新所有 LlmResponse 构造点**

Run: `grep -rn "new LlmResponse(" src/main/java src/test/java`
Expected: 列出所有构造点

每个构造点改为 `LlmResponse.simple(...)` 静态工厂（迁移期）或新构造器（如 AbstractProviderAdapter.toLlmResponse）。

- [ ] **Step 3：编译验证**

Run: `mvn compile -q`
Expected: SUCCESS

- [ ] **Step 4：跑测试，无回归**

Run: `mvn test -q`
Expected: PASS

### Task 4.2：创建 LlmStreamEvent sealed interface 与 6 个 record

**Files:** 7 个新文件在 `src/main/java/com/lifepilot/llm/stream/`

- [ ] **Step 1：创建 LlmStreamEvent**

```java
package com.lifepilot.llm.stream;

/**
 * LLM 流式事件 — sealed interface，5 种具体事件 + ErrorEvent。
 *
 * @author zsg
 * @since 2026-04-27
 */
public sealed interface LlmStreamEvent
        permits ReasoningChunk, ContentChunk, ToolCallDelta, UsageEvent, DoneEvent, ErrorEvent {
}
```

- [ ] **Step 2：创建 6 个 record**

```java
// ReasoningChunk.java
package com.lifepilot.llm.stream;

import org.springframework.lang.Nullable;

public record ReasoningChunk(String delta, @Nullable String signature) implements LlmStreamEvent {}

// ContentChunk.java
public record ContentChunk(String delta) implements LlmStreamEvent {}

// ToolCallDelta.java
public record ToolCallDelta(int index, @Nullable String id, @Nullable String name, String argumentsDelta) implements LlmStreamEvent {}

// UsageEvent.java
public record UsageEvent(int inputTokens, int outputTokens, @Nullable Integer reasoningTokens, int cachedInputTokens) implements LlmStreamEvent {}

// DoneEvent.java
public record DoneEvent(@Nullable String finishReason) implements LlmStreamEvent {}

// ErrorEvent.java
public record ErrorEvent(String code, String message) implements LlmStreamEvent {}
```

每个文件加 `package com.lifepilot.llm.stream;` 和必要 imports。

- [ ] **Step 3：编译验证**

Run: `mvn compile -q`
Expected: SUCCESS

### Task 4.3：重写 StreamingLlmResponse

**Files:**
- Modify: `src/main/java/com/lifepilot/llm/StreamingLlmResponse.java`

- [ ] **Step 1：写新 StreamingLlmResponse**

```java
package com.lifepilot.llm;

import com.lifepilot.llm.stream.LlmStreamEvent;
import reactor.core.publisher.Flux;

/**
 * 流式 LLM 响应包装 — 携带 Flux<LlmStreamEvent> + provider/model 元信息。
 *
 * @param events     LlmStreamEvent 流
 * @param providerId Provider ID
 * @param modelId    模型 ID
 * @author zsg
 * @since 2026-04-27
 */
public record StreamingLlmResponse(
        Flux<LlmStreamEvent> events,
        String providerId,
        String modelId
) {
}
```

- [ ] **Step 2：编译验证（预期 FAIL，所有消费 stream() 返回 Flux<String> 的代码都需要改）**

Run: `mvn compile -q`
Expected: 多处 FAIL — 主要是 StreamingCallback 用 `streamingResponse.stream()` 拿 `Flux<String>` 不再可用

### Task 4.4：升级 StreamingCallback 消费 LlmStreamEvent

**Files:**
- Modify: `src/main/java/com/lifepilot/agent/callback/StreamingCallback.java`

这是大改动（700+ 行原文件）。核心逻辑保持：先拿 ChatResponse 处理 tool call，但流式 token / reasoning 来源改为消费 `Flux<LlmStreamEvent>`。

- [ ] **Step 1：替换文本流式分支（callTextStreaming 第 296-349 行的 chunk 处理）**

把原本 `chatModelInfo.chatModel().stream(prompt)` 直接拿 ChatResponse 的逻辑，改为：
1. 通过 generationRouter 拿 `StreamingLlmResponse` 而不是 ChatModel；
2. 消费 `events` 流：

```java
StreamingLlmResponse streaming = generationRouter.streamWithInfo(scene, preferredProviderId, prompt, toolCallbacks);
this.providerId = streaming.providerId();
this.modelId = streaming.modelId();

streaming.events()
    .takeWhile(ev -> !cancellationToken.isCancelled() && sseManager.getEmitter(streamId) != null)
    .doOnNext(ev -> {
        switch (ev) {
            case com.lifepilot.llm.stream.ReasoningChunk r -> pushReasoningToSse(r.delta());
            case com.lifepilot.llm.stream.ContentChunk c -> {
                contentBuilder.append(c.delta());
                Instant now = Instant.now();
                if (firstTokenTime[0] == null) firstTokenTime[0] = now;
                markFirstModelToken(now);
                pushTokenToSse(c.delta());
            }
            case com.lifepilot.llm.stream.ToolCallDelta tcd -> toolCallAggregator.merge(tcd);
            case com.lifepilot.llm.stream.UsageEvent u -> {
                accumulatedPromptTokens[0] = u.inputTokens();
                accumulatedCompletionTokens[0] = u.outputTokens();
                accumulatedCachedTokens[0] = u.cachedInputTokens();
            }
            case com.lifepilot.llm.stream.DoneEvent d -> { /* 流结束 */ }
            case com.lifepilot.llm.stream.ErrorEvent err -> {
                this.streamingError = new RuntimeException(err.code() + ": " + err.message());
            }
        }
    })
    .doOnError(e -> { this.streamingError = e instanceof Exception ex ? ex : new RuntimeException(e); })
    .blockLast();
```

- [ ] **Step 2：新增 pushReasoningToSse 方法**

```java
private void pushReasoningToSse(String reasoning) {
    if (reasoning == null || reasoning.isEmpty()) return;
    var data = Map.<String, Object>of(
            "sessionId", sessionId,
            "turnId", turnId,
            "delta", reasoning
    );
    if (eventBuffer != null) {
        eventBuffer.offer(SseEventType.REASONING, data);
    } else {
        sseManager.sendEvent(streamId, SseEventType.REASONING, data);
    }
}
```

- [ ] **Step 3：在 SseEventType 枚举加 REASONING 值**

修改 `src/main/java/com/lifepilot/interaction/web/sse/SseEventType.java`，加 `REASONING("reasoning")` 枚举值。

- [ ] **Step 4：在 GenerationRouter 加 streamWithInfo 方法**

修改 `src/main/java/com/lifepilot/generation/router/GenerationRouter.java`，添加：

```java
public StreamingLlmResponse streamWithInfo(String scene, @Nullable String preferredProviderId,
                                           Prompt prompt, List<ToolCallback> toolCallbacks) {
    // 复用 getChatModelWithInfo 拿到 adapter，转调 adapter.streamEvents()
    var info = getChatModelWithInfo(scene, preferredProviderId, null);
    AbstractProviderAdapter adapter = (AbstractProviderAdapter) registry.getAdapter(info.serviceId());
    Flux<LlmStreamEvent> events = adapter.streamEvents(prompt, toolCallbacks);
    return new StreamingLlmResponse(events, info.serviceId(), info.modelName());
}
```

- [ ] **Step 5：在 AbstractProviderAdapter 加 streamEvents() 抽象方法**

```java
public abstract Flux<LlmStreamEvent> streamEvents(Prompt prompt, List<ToolCallback> toolCallbacks);
```

- [ ] **Step 6：在 OpenAiBaseProviderAdapter 实现 streamEvents()**

按 thinkingProtocol.extractReasoning() 把 Spring AI ChatResponse 流转成 LlmStreamEvent 流。具体实现：

```java
@Override
public Flux<LlmStreamEvent> streamEvents(Prompt prompt, List<ToolCallback> toolCallbacks) {
    // 把 thinking 字段 + extra_body 注入 prompt
    var requestBuilder = new com.lifepilot.llm.thinking.RequestBuilder();
    var thinkingMode = config.thinkingMode();  // 假设 ProviderConfig 有此字段（Phase 6 加）
    thinkingProtocol.applyToRequest(requestBuilder, thinkingMode);

    // 构造 ChatOptions（OpenAiChatOptions reasoning_effort 等原生字段从 requestBuilder.chatOptionsExtras 读）
    // 注入 extra_body 由 RestClient 拦截器（Phase 7 ProbeModelsService 共享拦截器）

    return chatModel.stream(prompt).flatMap(this::chunkToEvents);
}

private Flux<LlmStreamEvent> chunkToEvents(ChatResponse chunk) {
    // ... 把 chunk 解析成 ReasoningChunk / ContentChunk / ToolCallDelta / UsageEvent
    // ChatResponse 的 raw json 通过 metadata.getNativeUsage() 等暴露
    // 简化版：先发 ContentChunk，UsageEvent 在最后一个 chunk
    var events = new ArrayList<LlmStreamEvent>();
    var output = chunk.getResult() != null ? chunk.getResult().getOutput() : null;
    if (output != null) {
        String text = output.getText();
        if (text != null && !text.isEmpty()) {
            events.add(new ContentChunk(text));
        }
    }
    return Flux.fromIterable(events);
}
```

注意：reasoning 提取需要拿到原 SSE chunk 的 raw JSON，Spring AI 的 ChatResponse 不直接暴露。完整方案需要在 OpenAiApi 层加一个 RestClient 拦截器捕获原始 SSE chunk 旁路解析。**简化版**：先依赖 Spring AI 后续版本支持 reasoning_content（1.x 末期版本可能加），当前 chunkToEvents 不发 ReasoningChunk，等 Spring AI 支持后再扩展，或绕路写自定义 OpenAI HTTP 客户端。

**简化策略**：本次重构在 streamEvents 里只先打通 ContentChunk + UsageEvent + DoneEvent；ReasoningChunk 作为已知 TODO 留给 Phase 10 集成测试阶段补完（直接从 raw HTTP SSE 解析）。

- [ ] **Step 7：编译验证**

Run: `mvn compile -q`
Expected: SUCCESS（部分行为打桩）

- [ ] **Step 8：跑测试，无回归**

Run: `mvn test -q`
Expected: 现有测试 PASS（除了部分 streaming 用例可能需要更新断言）

- [ ] **Step 9：commit Phase 4**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(llm): LlmResponse 富字段化 + Stream 事件流

- LlmResponse 加 reasoningContent / reasoningSignature / toolCalls /
  providerMetadata / reasoningTokens / cachedInputTokens
- 新增 LlmStreamEvent sealed interface + 6 个 record
- StreamingLlmResponse 包装 Flux<LlmStreamEvent>
- StreamingCallback 改成消费 LlmStreamEvent，按 sealed pattern matching 分派
- SseEventType 加 REASONING 事件类型
- AbstractProviderAdapter 加抽象方法 streamEvents()，OpenAiBase 实现 ContentChunk + UsageEvent
- ReasoningChunk 解析待 Phase 10 集成测试时补完（需要 raw SSE chunk 旁路）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5：ChatHistoryAssembler + payload_json（commit 5）

**目标**：多轮 history 装载按 ProviderProfile.historyRules 序列化；assistant payload_json 扩展含 reasoning。

### Task 5.1：创建 ProviderMessage 与扩展 AssistantMessageBuilder

**Files:**
- Create: `src/main/java/com/lifepilot/llm/history/ProviderMessage.java`
- Modify: `src/main/java/com/lifepilot/llm/history/AssistantMessageBuilder.java`

- [ ] **Step 1：创建 ProviderMessage**

```java
package com.lifepilot.llm.history;

import com.lifepilot.llm.ToolCall;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 适配器内部消息类型 — ChatHistoryAssembler 输出，Adapter 消费。
 *
 * @param role             system / user / assistant / tool
 * @param content          消息文本
 * @param reasoningContent 仅 assistant 角色有效
 * @param reasoningSignature  仅 Anthropic 有效
 * @param toolCalls        仅 assistant 角色有效
 * @param providerExtras   厂商私有字段（多轮回传需要的 extra_body 等）
 * @author zsg
 * @since 2026-04-27
 */
public record ProviderMessage(
        String role,
        String content,
        @Nullable String reasoningContent,
        @Nullable String reasoningSignature,
        List<ToolCall> toolCalls,
        Map<String, Object> providerExtras
) {
    public ProviderMessage {
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        providerExtras = providerExtras != null ? Map.copyOf(providerExtras) : Map.of();
    }

    public static ProviderMessage user(String content) {
        return new ProviderMessage("user", content, null, null, List.of(), Map.of());
    }

    public static ProviderMessage system(String content) {
        return new ProviderMessage("system", content, null, null, List.of(), Map.of());
    }
}
```

- [ ] **Step 2：扩展 AssistantMessageBuilder**

```java
package com.lifepilot.llm.history;

import com.lifepilot.llm.ToolCall;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AssistantMessageBuilder {
    @Nullable private String content;
    @Nullable private String reasoningContent;
    @Nullable private String reasoningSignature;
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final Map<String, Object> extras = new HashMap<>();

    public AssistantMessageBuilder content(String content) { this.content = content; return this; }
    public AssistantMessageBuilder reasoningContent(@Nullable String r) { this.reasoningContent = r; return this; }
    public AssistantMessageBuilder reasoningSignature(@Nullable String s) { this.reasoningSignature = s; return this; }
    public AssistantMessageBuilder addToolCall(ToolCall tc) { toolCalls.add(tc); return this; }
    public AssistantMessageBuilder putExtra(String key, Object value) { extras.put(key, value); return this; }

    public ProviderMessage build() {
        return new ProviderMessage("assistant",
                content != null ? content : "",
                reasoningContent, reasoningSignature,
                List.copyOf(toolCalls), Map.copyOf(extras));
    }

    @Nullable public String content() { return content; }
    @Nullable public String reasoningContent() { return reasoningContent; }
    @Nullable public String reasoningSignature() { return reasoningSignature; }
    public Map<String, Object> extras() { return Map.copyOf(extras); }
}
```

### Task 5.2：实现 ChatHistoryAssembler

**Files:**
- Create: `src/main/java/com/lifepilot/llm/history/ChatHistoryAssembler.java`
- Test: `src/test/java/com/lifepilot/llm/history/ChatHistoryAssembler_装载规则测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.llm.history;

import com.lifepilot.llm.profile.MultiTurnHistoryRules;
import com.lifepilot.llm.profile.ReasoningInjectionFormat;
import com.lifepilot.llm.thinking.DeepSeekThinkingProtocol;
import com.lifepilot.llm.thinking.NoopThinkingProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHistoryAssembler_装载规则测试 {

    @Test
    void DeepSeek_规则下_assistant_消息回传_reasoning_content() {
        var assembler = new ChatHistoryAssembler();
        var prevAssistant = Map.<String, Object>of(
                "role", "assistant",
                "content", "final",
                "reasoning_content", "上一轮思考"
        );
        var rules = MultiTurnHistoryRules.contentOnlyReasoning();
        var protocol = new DeepSeekThinkingProtocol();
        var msgs = assembler.assemble(
                List.of(prevAssistant, Map.of("role", "user", "content", "继续")),
                rules, protocol);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).reasoningContent()).isEqualTo("上一轮思考");
    }

    @Test
    void OpenAI_规则下_assistant_消息不含_reasoning() {
        var assembler = new ChatHistoryAssembler();
        var prevAssistant = Map.<String, Object>of(
                "role", "assistant", "content", "final");
        var rules = MultiTurnHistoryRules.standard();
        var protocol = new NoopThinkingProtocol();
        var msgs = assembler.assemble(
                List.of(prevAssistant, Map.of("role", "user", "content", "继续")),
                rules, protocol);
        assertThat(msgs.get(0).reasoningContent()).isNull();
    }

    @Test
    void DeepSeek_规则下_assistant_缺_reasoning_补空字符串() {
        var assembler = new ChatHistoryAssembler();
        var prevAssistant = Map.<String, Object>of("role", "assistant", "content", "final");
        var msgs = assembler.assemble(
                List.of(prevAssistant),
                MultiTurnHistoryRules.contentOnlyReasoning(),
                new DeepSeekThinkingProtocol());
        assertThat(msgs.get(0).reasoningContent()).isEqualTo("");
    }
}
```

- [ ] **Step 2：运行失败**

Run: `mvn test -Dtest=ChatHistoryAssembler_装载规则测试 -q`
Expected: FAIL

- [ ] **Step 3：实现**

```java
package com.lifepilot.llm.history;

import com.lifepilot.llm.profile.MultiTurnHistoryRules;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多轮 history 装载器 — 按 ProviderProfile.historyRules + ThinkingProtocol 把
 * SessionTranscriptEntries 反序列化的 payload Map 序列化为适配器消费的 ProviderMessage。
 *
 * @author zsg
 * @since 2026-04-27
 */
@Component
public class ChatHistoryAssembler {

    /**
     * 装载历史消息。
     *
     * @param payloads 按时间序的 payload_json 反序列化结果列表
     * @param rules    Provider 多轮规则
     * @param protocol Provider thinking 协议
     * @return 适配器可消费的 ProviderMessage 列表
     */
    public List<ProviderMessage> assemble(List<Map<String, Object>> payloads,
                                          MultiTurnHistoryRules rules,
                                          ThinkingProtocol protocol) {
        var result = new ArrayList<ProviderMessage>(payloads.size());
        for (var payload : payloads) {
            String role = String.valueOf(payload.getOrDefault("role", "user"));
            String content = String.valueOf(payload.getOrDefault("content", ""));
            switch (role) {
                case "system" -> result.add(ProviderMessage.system(content));
                case "user" -> result.add(ProviderMessage.user(content));
                case "assistant" -> result.add(buildAssistant(payload, rules, protocol));
                default -> result.add(new ProviderMessage(role, content, null, null,
                        List.of(), Map.of()));
            }
        }
        return List.copyOf(result);
    }

    private ProviderMessage buildAssistant(Map<String, Object> payload,
                                           MultiTurnHistoryRules rules,
                                           ThinkingProtocol protocol) {
        var builder = new AssistantMessageBuilder()
                .content(String.valueOf(payload.getOrDefault("content", "")));
        if (rules.injectReasoning()) {
            protocol.injectHistoryReasoning(builder, payload);
        }
        // tool_calls 注入（按 Phase 7 实施时扩展）
        return builder.build();
    }
}
```

- [ ] **Step 4：运行通过**

Run: `mvn test -Dtest=ChatHistoryAssembler_装载规则测试 -q`
Expected: PASS（3 tests）

### Task 5.3：扩展 payload_json 持久化

**Files:**
- Modify: `src/main/java/com/lifepilot/interaction/web/service/ChatTurnService.java`
- Modify: `src/main/java/com/lifepilot/interaction/web/service/ChatSessionService.java`

- [ ] **Step 1：修改 ChatTurnService 写 payload_json 时附加新字段**

定位 `ChatTurnService` 中持久化 assistant 消息的位置（grep `payload_json` / `assistantEntry`），在序列化时把 `LlmResponse` 的 reasoningContent / reasoningSignature / toolCalls / providerMetadata 写入 payload Map：

```java
// 在持久化 assistant 消息处
Map<String, Object> payload = new LinkedHashMap<>();
payload.put("role", "assistant");
payload.put("content", llmResponse.content());
if (llmResponse.reasoningContent() != null) {
    payload.put("reasoning_content", llmResponse.reasoningContent());
}
if (llmResponse.reasoningSignature() != null) {
    payload.put("reasoning_signature", llmResponse.reasoningSignature());
}
if (!llmResponse.toolCalls().isEmpty()) {
    payload.put("tool_calls", llmResponse.toolCalls());
}
if (!llmResponse.providerMetadata().isEmpty()) {
    payload.put("provider_metadata", llmResponse.providerMetadata());
}
payload.put("model_id", llmResponse.modelName());
payload.put("provider_id", llmResponse.providerId());
payload.put("tokens", Map.of(
        "input", llmResponse.inputTokens(),
        "output", llmResponse.outputTokens(),
        "reasoning", llmResponse.reasoningTokens() != null ? llmResponse.reasoningTokens() : 0,
        "cached_input", llmResponse.cachedInputTokens()
));
String payloadJson = objectMapper.writeValueAsString(payload);
// ... 后续把 payloadJson 写入 session_transcript_entries
```

- [ ] **Step 2：在 ChatSessionService 历史回查时反序列化新字段（保持向后兼容）**

历史 payload 没有 reasoning_content 字段时按 null 处理（已有的 Map.get 行为天然兼容）。

- [ ] **Step 3：编译 + 测试**

Run: `mvn compile && mvn test -q`
Expected: SUCCESS

### Task 5.4：commit Phase 5

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(llm): ChatHistoryAssembler + payload_json 扩展

- 新增 com.lifepilot.llm.history 包：ProviderMessage / AssistantMessageBuilder /
  ChatHistoryAssembler
- 按 ProviderProfile.historyRules + ThinkingProtocol 装载多轮 history
- ChatTurnService 持久化 assistant payload_json 时附加 reasoning_content /
  reasoning_signature / tool_calls / provider_metadata / tokens 字段
- 反序列化保持向后兼容（旧 payload 无 reasoning 字段时按 null）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 6：V32 迁移 + ModelServiceEntity 重写（commit 6）

**目标**：model_service 表重建，新 schema 含 profile_id / is_reasoning / thinking_mode；Entity 重写。

### Task 6.1：写 V32 Flyway 迁移

**Files:**
- Create: `src/main/resources/db/migration/V32__model_service_provider_profile.sql`

- [ ] **Step 1：写迁移 SQL**

```sql
-- V32__model_service_provider_profile.sql
-- 项目未上线，model_service 表直接重建，不做数据迁移
-- 新 schema 引入 ProviderProfile 数据驱动 + 推理模型配置

DROP TABLE IF EXISTS model_service;

CREATE TABLE model_service (
    id                            TEXT PRIMARY KEY,
    kind                          TEXT NOT NULL,
    profile_id                    TEXT NOT NULL,
    api_url                       TEXT NOT NULL,
    api_key                       TEXT,
    model_name                    TEXT NOT NULL,
    timeout_seconds               INTEGER NOT NULL DEFAULT 30,
    priority                      INTEGER NOT NULL DEFAULT 0,
    enabled                       INTEGER NOT NULL DEFAULT 1,
    is_reasoning                  INTEGER NOT NULL DEFAULT 0,
    thinking_mode                 TEXT    NOT NULL DEFAULT 'auto',
    supported_scenes_json         TEXT NOT NULL DEFAULT '[]',
    generation_capabilities_json  TEXT NOT NULL DEFAULT '[]',
    metadata_json                 TEXT NOT NULL DEFAULT '{}',
    display_name                  TEXT,
    description                   TEXT,
    created_at                    TEXT NOT NULL,
    updated_at                    TEXT NOT NULL
);

CREATE INDEX idx_model_service_kind     ON model_service(kind, enabled);
CREATE INDEX idx_model_service_profile  ON model_service(profile_id);
```

- [ ] **Step 2：启动应用验证迁移**

Run: `mvn spring-boot:run`
Expected: 启动日志含 `Successfully applied migration V32`，无报错。Ctrl+C 关闭。

### Task 6.2：重写 ModelServiceEntity

**Files:**
- Modify: `src/main/java/com/lifepilot/modelservice/model/ModelServiceEntity.java`

- [ ] **Step 1：写新 Entity**

```java
package com.lifepilot.modelservice.model;

import com.lifepilot.llm.thinking.ThinkingMode;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 模型服务存储实体（重写版本）。
 *
 * <p>profileId 替代原 providerType，作为协议路由主键；
 * is_reasoning + thinking_mode 控制推理模型行为。
 *
 * @author zsg
 * @since 2026-04-27
 */
public record ModelServiceEntity(
        String id,
        ModelServiceKind kind,
        String profileId,
        String apiUrl,
        @Nullable String apiKey,
        String modelName,
        int timeoutSeconds,
        int priority,
        boolean enabled,
        boolean isReasoning,
        ThinkingMode thinkingMode,
        List<String> supportedScenes,
        Set<GenerationCapability> generationCapabilities,
        Map<String, Object> metadata,
        @Nullable String displayName,
        @Nullable String description
) {
    public ModelServiceEntity {
        Objects.requireNonNull(id, "服务 ID 不能为空");
        Objects.requireNonNull(kind, "服务类型不能为空");
        Objects.requireNonNull(profileId, "Profile ID 不能为空");
        Objects.requireNonNull(apiUrl, "API 地址不能为空");
        Objects.requireNonNull(modelName, "模型名称不能为空");
        Objects.requireNonNull(thinkingMode, "thinking_mode 不能为空");
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
        if (priority < 0) priority = 0;
        supportedScenes = supportedScenes != null ? List.copyOf(supportedScenes) : List.of();
        generationCapabilities = generationCapabilities != null ? Set.copyOf(generationCapabilities) : Set.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
```

- [ ] **Step 2：编译（预期 FAIL，所有引用 providerType 的地方都要改）**

Run: `mvn compile -q`
Expected: 多处 FAIL — `ModelServiceController` / `ModelServiceProviderConfigMapper` / `ModelServiceRepository` / `ModelServiceTemplate*` 都引用 ProviderType

### Task 6.3：适配 ModelServiceRepository 与 Mapper

**Files:**
- Modify: `src/main/java/com/lifepilot/modelservice/repository/ModelServiceRepository.java`
- Modify: `src/main/java/com/lifepilot/modelservice/support/ModelServiceProviderConfigMapper.java`

- [ ] **Step 1：Repository SELECT / INSERT 改新 schema**

把 `provider_type` 列读写全部改成 `profile_id`。INSERT/UPDATE 加 `is_reasoning, thinking_mode` 字段。

```java
// Repository SELECT
private static final String SELECT_BY_ID = """
        SELECT id, kind, profile_id, api_url, api_key, model_name,
               timeout_seconds, priority, enabled, is_reasoning, thinking_mode,
               supported_scenes_json, generation_capabilities_json,
               metadata_json, display_name, description
        FROM model_service WHERE id = :id
        """;

// RowMapper
private ModelServiceEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new ModelServiceEntity(
            rs.getString("id"),
            ModelServiceKind.valueOf(rs.getString("kind")),
            rs.getString("profile_id"),
            rs.getString("api_url"),
            rs.getString("api_key"),
            rs.getString("model_name"),
            rs.getInt("timeout_seconds"),
            rs.getInt("priority"),
            rs.getInt("enabled") == 1,
            rs.getInt("is_reasoning") == 1,
            ThinkingMode.fromString(rs.getString("thinking_mode")),
            parseJsonList(rs.getString("supported_scenes_json")),
            parseJsonCapabilitySet(rs.getString("generation_capabilities_json")),
            parseJsonMap(rs.getString("metadata_json")),
            rs.getString("display_name"),
            rs.getString("description")
    );
}
```

- [ ] **Step 2：Mapper 改成把 profile_id 转成 ProviderConfig.profileId**

```java
public ProviderConfig toProviderConfig(ModelServiceEntity e) {
    return new ProviderConfig(
            e.id(),
            e.profileId(),                  // 新字段
            ProviderType.fromProfile(...),  // 临时兜底，下一 step 改用 profile.baseAdapter
            e.apiUrl(), e.apiKey(), e.modelName(),
            e.timeoutSeconds(), e.priority(),
            e.supportedScenes(), e.generationCapabilities(),
            e.enabled(), 0, 0, 0, null, true,
            e.isReasoning(), e.thinkingMode()
    );
}
```

- [ ] **Step 3：彻底移除 ProviderType 引用，改成从 profile 读 baseAdapter（同时改 ProviderConfig 删除 type 字段）**

修改 `src/main/java/com/lifepilot/llm/config/ProviderConfig.java`：
- 删除 `ProviderType type` 字段
- 加 `String profileId` + `boolean isReasoning` + `ThinkingMode thinkingMode` 字段
- 删除 `isLocal()` 方法（改由 profile.baseAdapter 决定）

修改 `src/main/java/com/lifepilot/llm/config/ProviderType.java`：删除文件（项目内不再使用）。

修改所有引用 ProviderType / config.type() 的代码：
- 改成 `profileRegistry.get(config.profileId()).baseAdapter()`
- ProviderRegistry / ProviderHealthChecker / TEI 健康检查路径都需对应改

- [ ] **Step 4：编译验证**

Run: `mvn compile -q`
Expected: SUCCESS

- [ ] **Step 5：跑测试**

Run: `mvn test -q`
Expected: PASS

### Task 6.4：ModelServiceController 加 profile / thinking 字段

**Files:**
- Modify: `src/main/java/com/lifepilot/interaction/web/controller/ModelServiceController.java`
- Modify: 对应 DTO（Request/Response record）

- [ ] **Step 1：DTO 加 profileId / isReasoning / thinkingMode 字段**

```java
public record CreateModelServiceRequest(
        String id,
        ModelServiceKind kind,
        String profileId,           // 新增，必填
        String apiUrl,
        @Nullable String apiKey,
        String modelName,
        @Nullable Integer timeoutSeconds,
        @Nullable Integer priority,
        @Nullable Boolean enabled,
        @Nullable Boolean isReasoning,
        @Nullable String thinkingMode,
        @Nullable List<String> supportedScenes,
        @Nullable Set<GenerationCapability> generationCapabilities,
        @Nullable Map<String, Object> metadata,
        @Nullable String displayName,
        @Nullable String description
) {}
```

- [ ] **Step 2：Controller 把字段透传到 Entity 构造**

- [ ] **Step 3：新增 GET /api/provider-profiles**

```java
@GetMapping("/api/provider-profiles")
public ApiResponse<List<ProviderProfileDto>> listProfiles() {
    var profiles = profileRegistry.all().stream()
            .map(p -> new ProviderProfileDto(p.id(), p.displayName(),
                    p.baseAdapter().name(), p.defaultBaseUrl(),
                    p.thinkingProtocol().name(),
                    p.capabilities().stream().map(Enum::name).toList()))
            .toList();
    return ApiResponse.success(profiles);
}

public record ProviderProfileDto(String id, String displayName, String baseAdapter,
                                 String defaultBaseUrl, String thinkingProtocol,
                                 List<String> capabilities) {}
```

- [ ] **Step 4：编译 + 测试**

Run: `mvn compile && mvn test -q`
Expected: SUCCESS

### Task 6.5：commit Phase 6

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modelservice): V32 迁移 + Entity 重写

- V32 重建 model_service 表（DROP + CREATE），新 schema 含
  profile_id / is_reasoning / thinking_mode 字段
- ModelServiceEntity 重写，profile_id 替代 provider_type
- ProviderConfig 删除 type 字段，加 profileId / isReasoning / thinkingMode
- 删除 ProviderType 类（不再使用）
- ModelServiceController 加 profile/thinking 字段
- 新增 GET /api/provider-profiles 列出内置 profile

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 7：probe 端点 + healthCheck 改造（commit 7）

**目标**：新增 `POST /api/model-services/probe-models` 端点，按 profile 拉模型清单；healthCheck 改走 /v1/models。

### Task 7.1：DTO 与 Service

**Files:**
- Create: `src/main/java/com/lifepilot/modelservice/probe/ProbeModelsRequest.java`
- Create: `src/main/java/com/lifepilot/modelservice/probe/ProbeModelsResponse.java`
- Create: `src/main/java/com/lifepilot/modelservice/probe/ProbeModelsService.java`

- [ ] **Step 1：DTO**

```java
package com.lifepilot.modelservice.probe;

public record ProbeModelsRequest(String profileId, String baseUrl, String apiKey) {}

public record ProbeModelsResponse(java.util.List<ModelInfo> models) {
    public record ModelInfo(String id, String name) {}
}
```

- [ ] **Step 2：写失败测试**

```java
package com.lifepilot.modelservice.probe;

import com.lifepilot.llm.profile.ProviderProfileRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProbeModelsService_探测端点测试 {

    private MockWebServer server;
    private ProbeModelsService service;
    private ProviderProfileRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        registry = new ProviderProfileRegistry();
        registry.init();
        service = new ProbeModelsService(registry);
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void OpenAI_兼容_v1_models_解析_data_id() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"data":[{"id":"deepseek-v4-pro"},{"id":"deepseek-chat"}]}
                        """));
        var req = new ProbeModelsRequest("deepseek-official",
                server.url("").toString().replaceAll("/$", ""), "sk-test");
        var resp = service.probe(req);
        assertThat(resp.models()).hasSize(2);
        assertThat(resp.models().get(0).id()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void Ollama_api_tags_解析_models_name() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("""
                        {"models":[{"name":"llama3:8b"},{"name":"qwen2:7b"}]}
                        """));
        var req = new ProbeModelsRequest("ollama-local",
                server.url("").toString().replaceAll("/$", ""), null);
        var resp = service.probe(req);
        assertThat(resp.models()).hasSize(2);
        assertThat(resp.models().get(0).id()).isEqualTo("llama3:8b");
    }
}
```

- [ ] **Step 3：实现 ProbeModelsService**

```java
package com.lifepilot.modelservice.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.lifepilot.llm.profile.ProviderProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型探测服务 — 按 profile.modelDiscovery 拉模型清单。
 *
 * @author zsg
 * @since 2026-04-27
 */
@Service
public class ProbeModelsService {

    private static final Logger log = LoggerFactory.getLogger(ProbeModelsService.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final ProviderProfileRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProbeModelsService(ProviderProfileRegistry registry) {
        this.registry = registry;
    }

    public ProbeModelsResponse probe(ProbeModelsRequest req) {
        var profile = registry.get(req.profileId());
        var endpoint = profile.modelDiscovery();
        String url = req.baseUrl().replaceAll("/$", "") + endpoint.path();

        var builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10));
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            String headerValue = endpoint.authHeaderFormat()
                    .replace("${apiKey}", req.apiKey());
            builder.header(endpoint.authHeaderName(), headerValue);
        }
        try {
            var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "探测失败: HTTP " + response.statusCode() + ", body=" + response.body());
            }
            var body = response.body();
            // 用 jsonpath 提取 model 清单
            List<String> ids = JsonPath.read(body, endpoint.responseModelsJsonPath());
            var models = ids.stream()
                    .map(id -> new ProbeModelsResponse.ModelInfo(id, id))
                    .toList();
            log.info("探测成功: profileId={}, count={}", req.profileId(), models.size());
            return new ProbeModelsResponse(models);
        } catch (Exception e) {
            throw new RuntimeException("探测端点请求失败: " + e.getMessage(), e);
        }
    }
}
```

`pom.xml` 加依赖：

```xml
<dependency>
    <groupId>com.jayway.jsonpath</groupId>
    <artifactId>json-path</artifactId>
    <version>2.9.0</version>
</dependency>
```

如已有则跳过。

- [ ] **Step 4：运行测试通过**

Run: `mvn test -Dtest=ProbeModelsService_探测端点测试 -q`
Expected: PASS（2 tests）

### Task 7.2：Controller 加 probe endpoint

**Files:**
- Modify: `src/main/java/com/lifepilot/interaction/web/controller/ModelServiceController.java`

- [ ] **Step 1：加 endpoint**

```java
@PostMapping("/api/model-services/probe-models")
public ApiResponse<ProbeModelsResponse> probeModels(@RequestBody ProbeModelsRequest req) {
    return ApiResponse.success(probeService.probe(req));
}
```

- [ ] **Step 2：编译 + 测试**

Run: `mvn compile && mvn test -q`
Expected: SUCCESS

### Task 7.3：healthCheck 改走 /v1/models

**Files:**
- Modify: `src/main/java/com/lifepilot/llm/adapter/AbstractProviderAdapter.java`

- [ ] **Step 1：改 healthCheck()**

```java
@Override
public boolean healthCheck() {
    try {
        // TEI 走 /health
        var profile = profileRegistry.get(config.profileId());  // 注入 registry
        if (profile.baseAdapter() == BaseAdapterType.TEI) {
            return checkHealthEndpoint(config.apiUrl());
        }
        // 优先走 /v1/models（毫秒级，不耗 token）
        try {
            var probeResp = probeService.probe(new ProbeModelsRequest(
                    config.profileId(), config.apiUrl(), config.apiKey()));
            return !probeResp.models().isEmpty();
        } catch (Exception probeFail) {
            log.debug("/v1/models 探测失败，回退 chat ping: {}", probeFail.getMessage());
        }
        // 回退：chat ping with max_tokens=10
        var chatOptions = OpenAiChatOptions.builder()
                .model(config.modelName())
                .maxTokens(10)
                .build();
        var prompt = new Prompt("ping", chatOptions);
        ChatResponse response = executeWithTimeout(
                () -> chatModel.call(prompt), HEALTH_CHECK_TIMEOUT);
        return response != null && response.getResult() != null
                && response.getResult().getOutput() != null;
    } catch (Exception e) {
        log.debug("Provider 健康检查失败: id={}, error={}", config.id(), e.getMessage());
        return false;
    }
}
```

把 `ProbeModelsService` / `ProviderProfileRegistry` 通过 setter 或构造器注入到 AbstractProviderAdapter。最干净是改成 ProviderAdapterFactory 构造时注入。

- [ ] **Step 2：编译 + 测试**

Run: `mvn compile && mvn test -q`
Expected: SUCCESS

### Task 7.4：commit Phase 7

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modelservice): /v1/models 探测端点 + healthCheck 改造

- 新增 com.lifepilot.modelservice.probe 包：
  ProbeModelsRequest / ProbeModelsResponse / ProbeModelsService
- POST /api/model-services/probe-models 按 profile.modelDiscovery
  发起 GET 请求，解析 model 清单
- healthCheck 优先走 /v1/models（毫秒级）；失败回退 chat ping with max_tokens=10
  - 解决推理模型 chat ping 超时被 cancel 误判 healthy=false 的问题
- 加 jsonpath 依赖

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 8：前端模型路由页改造（commit 8）

**目标**：模型路由页改成"挑 profile → 填 baseUrl/apiKey → 拉模型清单 → 选 model → 配置 is_reasoning + thinking_mode"。

### Task 8.1：API 客户端

**Files:**
- Create: `zhiwei-web/src/api/providerProfile.ts`
- Create: `zhiwei-web/src/api/probeModels.ts`

- [ ] **Step 1：providerProfile.ts**

```typescript
import { http } from './http';

export interface ProviderProfileDto {
  id: string;
  displayName: string;
  baseAdapter: 'OPENAI_BASE' | 'ANTHROPIC_BASE' | 'OLLAMA' | 'TEI';
  defaultBaseUrl: string;
  thinkingProtocol: 'DEEPSEEK' | 'QWEN' | 'OPENAI_REASONING_EFFORT' | 'ANTHROPIC' | 'NONE';
  capabilities: string[];
}

export async function listProviderProfiles(): Promise<ProviderProfileDto[]> {
  const res = await http.get('/api/provider-profiles');
  return res.data.data;
}
```

- [ ] **Step 2：probeModels.ts**

```typescript
import { http } from './http';

export interface ProbeModelsRequest {
  profileId: string;
  baseUrl: string;
  apiKey?: string;
}

export interface ModelInfo {
  id: string;
  name: string;
}

export async function probeModels(req: ProbeModelsRequest): Promise<ModelInfo[]> {
  const res = await http.post('/api/model-services/probe-models', req);
  return res.data.data.models;
}
```

### Task 8.2：ModelServiceManager.vue 重写

**Files:**
- Modify: `zhiwei-web/src/views/settings/ModelServiceManager.vue`（或对等文件）

- [ ] **Step 1：新增 ProfilePicker 子组件**

```vue
<!-- ProfilePicker.vue -->
<template>
  <div class="space-y-md">
    <label class="text-sm">选择 Provider 协议</label>
    <Select v-model="selectedId">
      <SelectTrigger class="w-full">
        <SelectValue placeholder="挑一个内置协议..." />
      </SelectTrigger>
      <SelectContent>
        <SelectItem v-for="p in profiles" :key="p.id" :value="p.id">
          {{ p.displayName }}
          <span class="text-xs text-muted-foreground ml-sm">
            ({{ p.thinkingProtocol === 'NONE' ? '非推理' : '支持推理' }})
          </span>
        </SelectItem>
      </SelectContent>
    </Select>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { listProviderProfiles, type ProviderProfileDto } from '@/api/providerProfile';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from 'reka-ui';

const profiles = ref<ProviderProfileDto[]>([]);
const selectedId = defineModel<string | null>();

const emit = defineEmits<{ change: [profile: ProviderProfileDto] }>();

listProviderProfiles().then(p => { profiles.value = p; });

watch(selectedId, (id) => {
  const p = profiles.value.find(p => p.id === id);
  if (p) emit('change', p);
});
</script>
```

- [ ] **Step 2：模型挑选区**

```vue
<template>
  <div v-if="selectedProfile" class="space-y-md">
    <Input v-model="form.baseUrl" :placeholder="selectedProfile.defaultBaseUrl" />
    <Input v-model="form.apiKey" type="password" placeholder="API Key（如需）" />
    <Button @click="onProbe" :disabled="probing">
      {{ probing ? '探测中...' : '拉取可用模型' }}
    </Button>
    <Select v-if="probedModels.length" v-model="form.modelName">
      <SelectTrigger><SelectValue placeholder="选择模型" /></SelectTrigger>
      <SelectContent>
        <SelectItem v-for="m in probedModels" :key="m.id" :value="m.id">{{ m.name }}</SelectItem>
      </SelectContent>
    </Select>
    <Input v-else v-model="form.modelName" placeholder="或手动输入模型名" />
  </div>
</template>

<script setup lang="ts">
const probedModels = ref<ModelInfo[]>([]);
const probing = ref(false);

async function onProbe() {
  probing.value = true;
  try {
    probedModels.value = await probeModels({
      profileId: selectedProfile.value!.id,
      baseUrl: form.value.baseUrl,
      apiKey: form.value.apiKey
    });
  } catch (e: any) {
    toast.error(`探测失败: ${e.message}`);
  } finally {
    probing.value = false;
  }
}
</script>
```

- [ ] **Step 3：is_reasoning + thinking_mode 配置区**

```vue
<template>
  <div class="space-y-md mt-md">
    <div class="flex items-center gap-sm">
      <Checkbox v-model="form.isReasoning" />
      <label class="text-sm">这是推理模型（支持思考链）</label>
    </div>
    <Select v-if="form.isReasoning" v-model="form.thinkingMode">
      <SelectTrigger><SelectValue /></SelectTrigger>
      <SelectContent>
        <SelectItem value="auto">auto — 用 provider 默认</SelectItem>
        <SelectItem value="enabled">enabled — 强制开启思考</SelectItem>
        <SelectItem value="disabled">disabled — 强制关闭思考</SelectItem>
      </SelectContent>
    </Select>
  </div>
</template>
```

- [ ] **Step 4：保存时 payload 包含 profileId / isReasoning / thinkingMode**

调用 `POST /api/model-services` 时把表单字段全部传过去。

- [ ] **Step 5：前端测试**

Run: `cd zhiwei-web && npm run test:run`
Expected: PASS

### Task 8.3：commit Phase 8

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(web): 模型路由页 profile 挑选 + 模型探测 UI

- 新增 API client：providerProfile / probeModels
- ModelServiceManager 重写：
  ProfilePicker → baseUrl/apiKey → 拉模型清单 → 选 model →
  勾 is_reasoning → 选 thinking_mode

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 9：前端 chat reasoning 流式 UI（commit 9）

**目标**：useChatStream 支持 reasoning 事件类型，MessageBubble 加 reasoning 折叠区域。

### Task 9.1：useChatStream 多事件类型

**Files:**
- Modify: `zhiwei-web/src/composables/useChatStream.ts`

- [ ] **Step 1：扩展 SSE 事件处理器**

```typescript
const eventSource = new EventSource(url);

eventSource.addEventListener('token', (ev) => {
  const data = JSON.parse(ev.data);
  // 现有逻辑：内容 token 流
  state.contentBuffer.value += data.content;
});

eventSource.addEventListener('reasoning', (ev) => {
  const data = JSON.parse(ev.data);
  // 新增：reasoning token 流
  state.reasoningBuffer.value += data.delta;
  state.isReasoningActive.value = true;
});

eventSource.addEventListener('done', (ev) => {
  state.isStreaming.value = false;
  state.isReasoningActive.value = false;  // 触发 UI 折叠
});
```

state 加：

```typescript
interface ChatStreamState {
  contentBuffer: Ref<string>;
  reasoningBuffer: Ref<string>;          // 新增
  isReasoningActive: Ref<boolean>;       // 新增（流式时为 true，结束自动折叠）
  isStreaming: Ref<boolean>;
}
```

### Task 9.2：ReasoningSection 组件

**Files:**
- Create: `zhiwei-web/src/components/chat/ReasoningSection.vue`

- [ ] **Step 1：写组件**

```vue
<template>
  <div v-if="reasoning" class="rounded-md border border-muted bg-muted/30 p-md text-sm">
    <button
      @click="expanded = !expanded"
      class="flex w-full items-center justify-between text-muted-foreground hover:text-foreground"
    >
      <span class="flex items-center gap-xs">
        <Brain :size="14" :class="active ? 'animate-pulse' : ''" />
        <span>{{ active ? '思考中...' : `已思考 ${formatDuration(durationMs)}` }}</span>
      </span>
      <ChevronDown :size="14" :class="expanded ? 'rotate-180' : ''" />
    </button>
    <div v-if="expanded || active" class="mt-md whitespace-pre-wrap text-muted-foreground">
      {{ reasoning }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { Brain, ChevronDown } from 'lucide-vue-next';

const props = defineProps<{
  reasoning: string;
  active: boolean;       // 流式时 true
  durationMs?: number;   // 完成后展示
}>();

const expanded = ref(props.active);

// 流式期间展开，完成后自动折叠
watch(() => props.active, (newVal) => {
  expanded.value = newVal;
});

function formatDuration(ms: number = 0): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}
</script>
```

### Task 9.3：MessageBubble 集成 ReasoningSection

**Files:**
- Modify: `zhiwei-web/src/components/chat/MessageBubble.vue`

- [ ] **Step 1：在 assistant 消息里嵌入 ReasoningSection**

```vue
<template>
  <div class="message-bubble">
    <ReasoningSection
      v-if="message.role === 'assistant' && (message.reasoningContent || isReasoningActive)"
      :reasoning="message.reasoningContent || reasoningBuffer"
      :active="isReasoningActive"
      :duration-ms="message.reasoningDurationMs"
    />
    <div class="message-content">{{ message.content }}</div>
  </div>
</template>

<script setup lang="ts">
import ReasoningSection from './ReasoningSection.vue';
import { useChatStream } from '@/composables/useChatStream';

const { reasoningBuffer, isReasoningActive } = useChatStream();
</script>
```

- [ ] **Step 2：前端测试**

Run: `cd zhiwei-web && npm run test:run`
Expected: PASS

### Task 9.4：commit Phase 9

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(web): chat reasoning 流式 UI

- useChatStream 支持 reasoning / done 事件类型
- 新增 ReasoningSection 组件：
  流式时自动展开 + 完成自动折叠 + 「已思考 X 秒」展示
- MessageBubble 在 assistant 消息中嵌入 ReasoningSection

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 10：集成测试 + E2E 冒烟（commit 10）

**目标**：补完 ReasoningChunk 流式提取（Phase 4 留的 TODO），用 MockWebServer 跑通 5 个 provider 的请求/响应/多轮契约。

### Task 10.1：补完 ReasoningChunk 流式提取

**Files:**
- Modify: `src/main/java/com/lifepilot/llm/adapter/OpenAiBaseProviderAdapter.java`

- [ ] **Step 1：在 OpenAiApi 构造时挂一个 SSE 旁路解析 Filter**

复用 `AbstractJsonBodyRewritingStrategy` 模式，但反向 — 拦截**响应** SSE chunk，旁路解析 `data: {...}` 行，把 reasoning_content 字段提取给 ThinkingProtocol。具体实现：

```java
// 在 ProviderAdapterFactory.createOpenAiBaseAdapter 给 OpenAiApi.Builder 加 filter
// 这里只在 Adapter 内部维护一个 reasoning chunk 队列，stream() 时从队列消费
```

完整实现细节因 Spring AI 1.1.3 内部 SSE 路径耦合较深，建议参考 Open WebUI 的 `merge_reasoning_content_in_choices` 思路：
- 启动时构造 OpenAiChatModel 时通过 `webClientBuilder` 挂 `ExchangeFilterFunction`
- 该 Filter 拦截响应 body 流，把每个 SSE `data:` chunk 旁路解析，发现 `reasoning_content` 字段后放入 ThreadLocal / 关联 stream id 的队列
- streamEvents() 在 chunkToEvents 之外定期从队列拉 ReasoningChunk 推到 Flux

考虑到复杂度，**可选简化方案**：放弃 Spring AI 路径，对推理模型走自定义 OpenAI HTTP 客户端（sse-eventsource library），完全自主解析 chunk → LlmStreamEvent，绕开 Spring AI Chat SDK。

- [ ] **Step 2：实现选定方案，加测试**

```java
@Test
void DeepSeek_流式_reasoning_chunk_先于_content_chunk_发出() throws Exception {
    // 用 MockWebServer 模拟 SSE：先 reasoning_content delta，再 content delta
    server.enqueue(new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("""
                    data: {"choices":[{"delta":{"reasoning_content":"我先想"}}]}
                    
                    data: {"choices":[{"delta":{"reasoning_content":"再想"}}]}
                    
                    data: {"choices":[{"delta":{"content":"答案"}}]}
                    
                    data: [DONE]
                    
                    """));
    // ... 配 ProviderConfig 指向 mock server，构造 adapter，调 streamEvents
    var events = adapter.streamEvents(prompt, List.of()).collectList().block();
    assertThat(events).hasSize(4);
    assertThat(events.get(0)).isInstanceOf(ReasoningChunk.class);
    assertThat(events.get(1)).isInstanceOf(ReasoningChunk.class);
    assertThat(events.get(2)).isInstanceOf(ContentChunk.class);
    assertThat(events.get(3)).isInstanceOf(DoneEvent.class);
}
```

- [ ] **Step 3：跑通**

Run: `mvn test -Dtest=*StreamingReasoningTest -q`
Expected: PASS

### Task 10.2：5 个 Provider Contract Test 套件

**Files:**
- Create: `src/test/java/com/lifepilot/llm/adapter/{DeepSeek,Qwen,OpenAiOfficial,Anthropic,Ollama}ProviderContractTest.java`

每个测试覆盖：
1. 请求注入：thinking_mode 三态分别构造请求，断言 ChatOptions / 请求 body 字段
2. 响应解析：fixture 喂 raw JSON → 断言 LlmResponse.reasoningContent
3. 多轮回传：构造含 reasoning 的 history → 断言厂商请求 body 字段对位

- [ ] **Step 1：DeepSeekProviderContractTest 完整代码**

```java
package com.lifepilot.llm.adapter;

import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekProviderContractTest {

    private MockWebServer server;
    // ... fixture setup

    @Test
    void thinking_mode_enabled_请求_body_含_extra_body_thinking_enabled() throws Exception {
        // 构造 adapter
        // 调 call("ping", null, Duration.ofSeconds(5))
        // 用 server.takeRequest() 拿到请求 body
        // 断言 body 包含 {"thinking":{"type":"enabled"}}
    }

    @Test
    void 响应_含_reasoning_content_LlmResponse_提取() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"choices":[{"message":{"content":"final","reasoning_content":"thought"}}],
                         "usage":{"prompt_tokens":10,"completion_tokens":5}}
                        """));
        var response = adapter.call("ping", null, Duration.ofSeconds(5));
        assertThat(response.reasoningContent()).isEqualTo("thought");
    }

    @Test
    void 第二轮_history_含_reasoning_content_请求_body_回传() throws Exception {
        // 构造含上一轮 reasoning_content 的 history
        // 调 ChatHistoryAssembler.assemble + adapter.call
        // server.takeRequest 断言 messages[N-1] 包含 reasoning_content 字段
    }
}
```

其他 4 个 provider 测试结构同 DeepSeek，关键差异行：
- Qwen：检查 `chat_template_kwargs.enable_thinking`
- OpenAI：检查 `reasoning_effort: medium`，断言无 reasoning 提取
- Anthropic：检查 `thinking: {type: adaptive}`，content array 解析
- Ollama：thinking 字段不下发

- [ ] **Step 2：跑通所有 contract test**

Run: `mvn test -Dtest=*ProviderContractTest -q`
Expected: 5 个测试类全 PASS

### Task 10.3：手工 E2E 冒烟检查清单

**Files:**
- Create: `docs/superpowers/plans/2026-04-27-llm-adapter-refactor-smoke.md`

- [ ] **Step 1：写检查清单**

```markdown
# LLM 适配器层重构 — 手工 E2E 冒烟检查清单

执行环境：dev 后端（mvn spring-boot:run）+ 前端（cd zhiwei-web && npm run dev）

## DeepSeek V4 系列

- [ ] 进入「设置 → 模型路由」
- [ ] 新建 model service：选 profile "DeepSeek 官方"，填 baseUrl/apiKey，点"拉取可用模型"，下拉选 deepseek-v4-pro
- [ ] 勾"这是推理模型"，thinking_mode 选 auto
- [ ] 保存 → 设为主对话模型
- [ ] 进入对话页，提问"分析下列数学题..."
- [ ] 观察：思考中 ... 文字滚动 → 答案出现 → reasoning 区域自动折叠
- [ ] 继续提问"再展开第 3 步" → 不应出现 400 错误
- [ ] thinking_mode 切 disabled，重新提问 → 无 reasoning 显示，响应明显加快

## OpenAI o-系列

- [ ] 配 model service：profile "OpenAI 官方"，model gpt-5
- [ ] 勾推理模型，thinking_mode auto
- [ ] 提问 → 不展示 reasoning（API 不返回），但 ZhiWei 不报错
- [ ] thinking_mode enabled → 后台日志看到 reasoning_effort=medium 字段下发

## Anthropic Claude Opus 4.7

- [ ] 配 model service：profile "Anthropic 官方"，model claude-opus-4-7
- [ ] 勾推理模型，thinking_mode auto
- [ ] 提问 → 流式 reasoning 滚动，含中间思考；多轮无 sign 错误

## Qwen3

- [ ] 配 model service：profile "通义千问 (DashScope)"，model qwen3-max
- [ ] 勾推理模型，thinking_mode auto
- [ ] 提问 + 多轮 → 不应 400

## 探测端点

- [ ] 配各家 provider 时点"拉取可用模型"按钮，应能正常返回模型清单
- [ ] 故意填错 apiKey → 失败 toast 显示具体错误
- [ ] /admin/health 端点查看 model service health 状态：推理模型不再误判 healthy=false

## 历史会话

- [ ] 关闭并重启应用，进入历史对话
- [ ] 之前 reasoning 折叠区域应能正常展开，文本完整保留
```

### Task 10.4：commit Phase 10

```bash
git add -A
git commit -m "$(cat <<'EOF'
test(llm): provider contract 套件 + E2E 冒烟清单

- 补完 ReasoningChunk 流式提取（OpenAiBaseProviderAdapter SSE 旁路）
- 5 个 ProviderContractTest 覆盖 thinking 三态请求 / 响应解析 / 多轮回传
- 手工 E2E 冒烟检查清单：DeepSeek / OpenAI / Anthropic / Qwen / 探测 / 历史

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Checklist

执行前我（plan 作者）应再核查：

**1. Spec 覆盖**
- ✅ ProviderProfile 数据驱动 → Phase 1
- ✅ 5 个 ThinkingProtocol → Phase 2
- ✅ Adapter 多态拆分 → Phase 3
- ✅ LlmResponse 富字段化 + LlmStreamEvent → Phase 4
- ✅ ChatHistoryAssembler + payload_json → Phase 5
- ✅ V32 迁移 + ModelServiceEntity 重写 → Phase 6
- ✅ /v1/models 探测端点 + healthCheck 改造 → Phase 7
- ✅ 前端模型路由页 → Phase 8
- ✅ 前端 chat reasoning UI → Phase 9
- ✅ Provider contract test + E2E 冒烟 → Phase 10

**2. 已知未完全展开的部分**
- Phase 4 Step 6 chunkToEvents 简化版（ReasoningChunk 解析延后到 Phase 10）
- Phase 10 Task 10.1 SSE 旁路解析具体实现策略待选（filter / 自定义 HTTP client），实施时挑一种
- Phase 8 前端代码片段是范式，具体跟 ZhiWei 现有 ModelServiceManager.vue 的字段映射要逐字段对齐

**3. Type Consistency**
- `ProviderProfile` 字段名前后一致：`thinkingProtocol` `historyRules` `modelDiscovery`
- `ThinkingMode` 枚举值：`AUTO` / `ENABLED` / `DISABLED`
- `LlmStreamEvent` 6 种实现：ReasoningChunk / ContentChunk / ToolCallDelta / UsageEvent / DoneEvent / ErrorEvent

**4. 跨 Phase 依赖正确**
- Phase 2 创建 AssistantMessageBuilder 骨架，Phase 5 扩展（已注明）
- Phase 3 Adapter 拆分时 ProviderConfig 临时加 profileId 占位，Phase 6 完整 schema 落地（已注明）
- Phase 4 chunkToEvents 简化版，Phase 10 补完（已注明）

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-27-llm-adapter-refactor.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — 派 fresh subagent 一 task 一执行，我夹审审，节奏快

**2. Inline Execution** — 当前会话执行，按 phase 设 checkpoint 给老板审

老板挑哪种？
