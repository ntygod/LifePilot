# 模型路由与能力配置重构方案

## 目标

本方案用于彻底重构当前 `LLMRouter / Provider / Reranker / 前端设置` 这一整条链路。

这不是兼容式整理，也不是局部修补。目标是一次性把最终形态定死：

- 生成、向量化、精排三类能力彻底解耦
- 场景路由只服务于生成能力
- 精排支持真正的本地 / API / LLM 三种执行模式
- 前端配置与后端能力模型完全一致
- 删除当前混杂语义，不保留兼容层

## 当前问题

当前实现存在结构性错位，不是单点 bug：

1. `LlmRouter` 同时承担生成、结构化输出、向量化、部分场景偏好路由，职责过宽。
2. `ProviderCapability.RERANK` 已定义，但主链路几乎未真正使用。
3. `LlmReranker` 实际是“用聊天模型做精排”，不是“Rerank Provider 适配器”。
4. `callEntity()` 路径没有统一超时保护，结构化输出类场景可能长时间阻塞。
5. `scene routing` 与 `capability routing` 混在一起，默认大量逻辑隐式回落到 `CHAT`。
6. 前端“精排类型 = LLM / API”抽象不完整，无法表达“本地 Rerank Provider”。
7. 前端可以选择 `bge-reranker` 这类本地精排服务，但后端 `LlmReranker` 只会按 `CHAT` Provider 去找模型，最终造成“配置看似生效，实际没命中”。
8. `/api/settings/providers` 只返回 `CHAT` Provider，导致前端列表天然偏向生成模型视角。
9. `user_settings` 里把强类型配置塞进 `scene_providers / reranker_config_json / knowledge_config_json`，长期可维护性差。
10. `ProviderAdapterFactory` 当前无论服务是否用于精排，都会围绕 `ChatModel` 建适配，能力边界不清晰。

## 最终原则

最终形态必须遵守以下原则：

1. 生成、向量化、精排是三套独立运行时，不再共享一套“默认按 CHAT 解释”的路由语义。
2. “场景”只属于生成能力，不属于 embedding 和 rerank。
3. “模型服务”必须先声明服务类型，再声明能力细节，前端不能再把本地 reranker 当作 LLM 选项展示。
4. 精排引擎必须显式区分：
   - 原生精排服务
   - LLM Pointwise
   - LLM Listwise
   - 关闭
5. 所有调用路径都必须统一具备：
   - 超时
   - 熔断
   - 重试 / 回退
   - 观测字段
6. 最终代码中不保留当前 `LlmRouter` 的兼容式分支，不做“旧接口继续可用”的中间层。

## 最终架构

### 一、模型服务注册层

引入统一的“模型服务”概念，替代当前泛化的 `Provider` 语义。

目标结构：

- `ModelServiceKind`
  - `GENERATION`
  - `EMBEDDING`
  - `RERANK`

- `ModelServiceConfig`
  - `id`
  - `kind`
  - `providerType`
  - `apiUrl`
  - `apiKey`
  - `modelName`
  - `timeoutSeconds`
  - `priority`
  - `enabled`
  - `metadataJson`

- `ModelServiceRegistry`
  - 按 `kind`
  - 按 `scene`
  - 按 `modelName`
  - 按 `id`
  查询服务

### 二、三套路由器

最终不再保留“一个 Router 包打天下”的设计，拆成三套：

- `GenerationRouter`
  - 负责文本生成、结构化输出、函数调用、流式输出、多模态生成
  - 替代当前 `LlmRouter` 的生成职责

- `EmbeddingRouter`
  - 负责 `embed / embedBatch`
  - 只处理 `EMBEDDING` 服务

- `RerankRouter`
  - 负责文档候选、记忆候选、通用候选精排
  - 统一调度原生 reranker 或 LLM rerank 策略

### 三、三类客户端

不再使用一个超宽接口的 `ProviderAdapter`。

拆成三类客户端：

- `GenerationServiceClient`
- `EmbeddingServiceClient`
- `RerankServiceClient`

以及三类工厂：

- `GenerationClientFactory`
- `EmbeddingClientFactory`
- `RerankClientFactory`

### 四、精排执行模式

精排最终采用显式引擎模式：

- `RerankExecutionMode.DISABLED`
- `RerankExecutionMode.NATIVE`
- `RerankExecutionMode.LLM_POINTWISE`
- `RerankExecutionMode.LLM_LISTWISE`

语义定义：

- `NATIVE`
  - 使用真正的 `RERANK` 服务
  - 例如 TEI / Jina / Cohere / 兼容 `/rerank` 的本地服务

- `LLM_POINTWISE`
  - 使用生成模型逐条评分

- `LLM_LISTWISE`
  - 使用生成模型一次性批量排序

`LlmReranker` 不再承担“全局精排器”角色，而是收缩为 `LlmPointwiseRerankStrategy` 与 `LlmListwiseRerankStrategy` 两个内部策略实现。

## 最终数据模型

### 一、模型服务表

新增并作为唯一事实源：

- `model_services`

建议字段：

- `id TEXT PRIMARY KEY`
- `kind TEXT NOT NULL`
- `provider_type TEXT NOT NULL`
- `api_url TEXT NOT NULL`
- `api_key TEXT`
- `model_name TEXT NOT NULL`
- `timeout_seconds INTEGER NOT NULL`
- `priority INTEGER NOT NULL DEFAULT 0`
- `enabled INTEGER NOT NULL DEFAULT 1`
- `scene_bindings_json TEXT DEFAULT '{}'`
- `generation_capabilities_json TEXT DEFAULT '[]'`
- `metadata_json TEXT DEFAULT '{}'`
- `created_at TEXT NOT NULL`
- `updated_at TEXT NOT NULL`

说明：

- `scene_bindings_json` 只对 `GENERATION` 服务生效
- `generation_capabilities_json` 只存生成子能力：
  - `CHAT`
  - `STRUCTURED_OUTPUT`
  - `FUNCTION_CALLING`
  - `STREAMING`
  - `VISION`
  - `NATIVE_AUDIO`
  - `NATIVE_VIDEO`
- `EMBEDDING` 与 `RERANK` 不再复用这些子能力枚举

### 二、生成路由设置表

新增：

- `generation_settings`

字段建议：

- `id TEXT PRIMARY KEY`
- `default_service_id TEXT`
- `scene_service_bindings_json TEXT DEFAULT '{}'`
- `created_at TEXT NOT NULL`
- `updated_at TEXT NOT NULL`

说明：

- 只保存生成服务路由
- 从这里彻底移除 `embedding`、`knowledge_rerank` 这类非生成场景

### 三、向量化设置表

新增：

- `embedding_settings`

字段建议：

- `id TEXT PRIMARY KEY`
- `default_service_id TEXT`
- `knowledge_base_service_id TEXT`
- `memory_service_id TEXT`
- `created_at TEXT NOT NULL`
- `updated_at TEXT NOT NULL`

说明：

- 可先保留 `default`，并允许对知识库和记忆分别覆盖

### 四、精排设置表

新增：

- `rerank_settings`

字段建议：

- `id TEXT PRIMARY KEY`
- `enabled INTEGER NOT NULL`
- `mode TEXT NOT NULL`
- `native_service_id TEXT`
- `llm_service_id TEXT`
- `knowledge_top_k INTEGER NOT NULL`
- `memory_enabled INTEGER NOT NULL`
- `memory_top_k INTEGER NOT NULL`
- `created_at TEXT NOT NULL`
- `updated_at TEXT NOT NULL`

说明：

- `mode=NATIVE` 时只使用 `native_service_id`
- `mode=LLM_POINTWISE / LLM_LISTWISE` 时只使用 `llm_service_id`
- 不再使用当前 `reranker_config_json`

### 五、删除项

最终删除：

- `user_settings.scene_providers` 的生成路由职责
- `user_settings.reranker_config_json`
- 当前以 JSON blob 形式承载的 reranker 主配置

## 最终后端模块结构

### 一、生成模块

目标目录：

- `com.lifepilot.generation.router`
- `com.lifepilot.generation.client`
- `com.lifepilot.generation.config`

核心类：

- `GenerationRouter`
- `GenerationRequest`
- `GenerationResponse`
- `GenerationScenePolicy`
- `GenerationServiceClient`
- `SpringAiGenerationClient`

职责：

- 所有聊天和结构化生成只走这里
- `generateStructured()` 内部必须带超时，不允许出现当前 `callEntity()` 无超时问题

### 二、向量化模块

目标目录：

- `com.lifepilot.embedding.router`
- `com.lifepilot.embedding.client`

核心类：

- `EmbeddingRouter`
- `EmbeddingServiceClient`
- `SpringAiEmbeddingClient`

职责：

- 只负责向量化
- 不再通过 `LlmRouter.embed()` 暴露

### 三、精排模块

目标目录：

- `com.lifepilot.rerank.router`
- `com.lifepilot.rerank.client`
- `com.lifepilot.rerank.strategy`

核心类：

- `RerankRouter`
- `RerankRequest`
- `RerankResult`
- `RerankSettingsService`
- `RerankServiceClient`
- `TeiRerankClient`
- `JinaRerankClient`
- `CohereRerankClient`
- `LlmPointwiseRerankStrategy`
- `LlmListwiseRerankStrategy`

职责：

- 所有知识库与记忆精排统一通过这里
- `DocumentRetriever` 和 `HybridRetriever` 不再直接依赖 `LlmReranker`

### 四、注册与选择层

目标目录：

- `com.lifepilot.model.registry`
- `com.lifepilot.model.selection`

核心类：

- `ModelServiceRegistry`
- `ModelServiceRepository`
- `ServiceSelectionPolicy`
- `ServiceHealthManager`

职责：

- 注册、健康检查、优先级排序、可选服务发现
- 不再混用“按 scene 找 provider”和“按 capability 找 provider”的歧义语义

## 最终路由语义

### 一、生成路由

生成服务选择顺序固定为：

1. 显式指定 `serviceId`
2. 显式指定 `modelName`
3. `generation_settings.scene_service_bindings_json`
4. `generation_settings.default_service_id`
5. 同 `scene` 的启用服务中按优先级选第一个

### 二、向量化路由

向量服务选择顺序固定为：

1. 显式指定 `serviceId`
2. 按用途读取 `embedding_settings`
   - `knowledge_base_service_id`
   - `memory_service_id`
   - `default_service_id`
3. 同 `EMBEDDING` 类型服务中按优先级选第一个

### 三、精排路由

精排服务选择顺序固定为：

1. 读取 `rerank_settings.mode`
2. 若 `DISABLED`，直接跳过
3. 若 `NATIVE`
   - 使用 `native_service_id`
   - 必须命中 `RERANK` 服务
4. 若 `LLM_POINTWISE / LLM_LISTWISE`
   - 使用 `llm_service_id`
   - 必须命中 `GENERATION` 服务

不再允许：

- 在 LLM 模式下选择 `RERANK` 服务
- 在 NATIVE 模式下选择聊天模型

## 最终前端形态

### 一、设置页结构

当前“模型服务 + 精排设置”需要拆成四块：

1. 生成模型服务
2. 生成路由设置
3. 向量模型设置
4. 精排引擎设置

### 二、模型服务管理器

模型服务创建时必须先选择 `服务类别`：

- 生成服务
- 向量服务
- 精排服务

不同类别展示不同字段：

- 生成服务
  - 场景绑定
  - 生成子能力
  - 流式、函数调用、视觉等

- 向量服务
  - 仅展示 embedding 相关字段

- 精排服务
  - 仅展示 rerank 相关字段
  - 例如 TEI / Jina / Cohere

### 三、生成路由 UI

只展示生成场景：

- `chat`
- `agent_reasoning`
- `agent_tool_calling`
- `agent_generation`
- `knowledge_extraction`
- `memory_compression`
- `document_summary`
- `skill_generation`

删除：

- `embedding`
- `knowledge_rerank`

### 四、精排设置 UI

不再使用当前的：

- `type = llm / api`

改为：

- `mode = disabled / native / llm_pointwise / llm_listwise`

字段联动规则：

- `native`
  - 下拉框只显示 `RERANK` 服务
- `llm_pointwise / llm_listwise`
  - 下拉框只显示 `GENERATION` 服务

因此：

- `bge-reranker` 这类服务只会出现在 `native` 模式
- 不会再出现在 `LLM` 精排模型列表里

## 最终链路调整

### 一、知识库精排

`DocumentRetriever` 改为依赖 `RerankRouter`。

### 二、记忆精排

`HybridRetriever` 改为依赖 `RerankRouter`。

### 三、结构化输出

所有 `callEntity()` 类路径改为走 `GenerationRouter.generateStructured(...)`，统一带：

- 超时
- 结构化输出优先
- JSON 修复降级
- 观测埋点

## 可观测性

新增统一观测字段：

- `serviceKind`
- `serviceId`
- `providerType`
- `scene`
- `executionMode`
- `candidateCount`
- `rerankTopK`
- `timeoutMs`
- `fallbackReason`

目标效果：

- 日志里能明确区分
  - `GenerationRouter`
  - `EmbeddingRouter`
  - `RerankRouter`
- 不再出现“看起来像用了 bge-reranker，实际是 qwen listwise”的不可见错配

## 删除清单

最终删除以下旧语义：

- `LlmRouter` 这个“大一统路由器”概念
- `ProviderAdapter` 的超宽接口
- `ProviderAdapterFactory` 的统一工厂语义
- `RerankerSettingsRequest.type = llm / api`
- `SettingsController.getProviders()` 只返回 `CHAT` Provider 的旧设计
- 把 `embedding`、`knowledge_rerank` 当作生成 scene 的路由配置

## 实施顺序

### 阶段 1：模型服务数据模型

- 新建 `model_services`
- 新建 `generation_settings`
- 新建 `embedding_settings`
- 新建 `rerank_settings`
- 删除旧配置承载职责

### 阶段 2：三套路由器与客户端

- 落地 `GenerationRouter`
- 落地 `EmbeddingRouter`
- 落地 `RerankRouter`
- 实现原生 rerank 客户端
- 实现 LLM rerank 策略

### 阶段 3：检索与记忆改接

- `DocumentRetriever -> RerankRouter`
- `HybridRetriever -> RerankRouter`
- `SemanticMemory / KnowledgeBase` 改接 `EmbeddingRouter`

### 阶段 4：前端设置页重做

- 重做模型服务管理器
- 重做生成路由设置
- 重做向量设置
- 重做精排设置

### 阶段 5：旧模块删除

- 删除旧 `LlmRouter`
- 删除旧 `ProviderAdapter` 体系
- 删除旧 reranker 配置接口与前端兼容逻辑

## 验收标准

满足以下条件才算完成：

1. 选择本地 `TEI bge-reranker` 后，精排日志明确显示命中 `RerankRouter + TeiRerankClient`。
2. `bge-reranker` 不再出现在 LLM 精排模型列表中。
3. `knowledge_rerank` 与 `embedding` 不再出现在生成场景路由配置里。
4. 结构化输出与精排相关调用全部具备统一超时控制。
5. 前端设置页能明确区分：
   - 生成服务
   - 向量服务
   - 精排服务
6. `DocumentRetriever` 与 `HybridRetriever` 不再直接依赖 `LlmReranker`。
7. 全仓不再保留旧 `type=llm/api` 的精排配置语义。
8. 无兼容层，无“双写双读”的长期残留。

## 决策结论

最终采用以下固定结论，不再反复讨论：

1. `LlmRouter` 不继续修补，直接拆。
2. 精排不是 LLM 路由的附属能力，而是独立能力域。
3. 本地 reranker 必须作为真正的 `RERANK` 服务接入，而不是借道聊天模型配置。
4. 前端配置必须按能力域建模，不能再按“LLM Provider 一把梭”展示。
5. 这次重构以最终清晰度为第一目标，不保留兼容式旧口径。
