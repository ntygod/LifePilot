# LLM 多模型路由 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.generation.router`、`com.lifepilot.embedding.router`、`com.lifepilot.rerank.router`、`com.lifepilot.llm.multimodal`
> **最后更新**：2026-03

## 1. 功能概述

LLM 多模型路由为知微提供统一的大模型调用能力，采用 **4 路由器分治架构**：生成路由（GenerationRouter）、向量路由（EmbeddingRouter）、精排路由（RerankRouter）和多模态路由（MultimodalRouter）。每个路由器拥有独立的候选选择逻辑和客户端工厂，共享熔断器与语义缓存基础设施。

模型服务通过数据库（`ModelServiceRegistry`）统一注册管理，支持运行时动态增删、厂商模板快速接入，无需重启。系统自动根据场景、用途和能力要求选择最优服务，并在服务故障时无感切换到备选方案。

## 2. 核心特性

### 2.1 4 路由器分治

| 路由器 | 职责 | 入口类 |
|--------|------|--------|
| **GenerationRouter** | 文本生成、流式输出、结构化输出、ChatClient/ChatModel 获取 | `com.lifepilot.generation.router.GenerationRouter` |
| **EmbeddingRouter** | 单条文本向量化、批量向量化 | `com.lifepilot.embedding.router.EmbeddingRouter` |
| **RerankRouter** | 文档精排、记忆精排（原生 API / LLM Pointwise / LLM Listwise） | `com.lifepilot.rerank.router.RerankRouter` |
| **MultimodalRouter** | 视觉理解、音频理解、视频理解；纯文本自动委托 GenerationRouter | `com.lifepilot.llm.multimodal.MultimodalRouter` |

### 2.2 DB 驱动的模型服务注册

模型服务通过 `ModelServiceRegistry` 管理，底层存储在 `model_services` 数据库表。相比 YAML 静态配置：

- **运行时动态增删**：通过 API 或前端管理界面操作，无需重启
- **厂商模板**：`ModelServiceTemplate` 预定义厂商配置（API URL、默认能力、推荐模型），用户一键创建服务实例
- **模型名模糊匹配**：按精确匹配 → 双向包含进行服务查找，方便用户指定模型
- **服务类型分类**：每个服务归属 `GENERATION`、`EMBEDDING` 或 `RERANK` 类型

### 2.3 Per-Capability 设置

每个路由维度拥有独立的设置实体，存储在数据库中，支持前端动态配置：

- **GenerationSettingsEntity**：默认服务 ID + 场景到服务的绑定映射（`sceneServiceBindings`），可为不同场景指定不同的生成服务
- **EmbeddingSettingsEntity**：按用途区分 —— 默认服务、知识库专用服务（`knowledgeBaseServiceId`）、记忆专用服务（`memoryServiceId`）
- **RerankSettingsEntity**：精排开关、执行模式（DISABLED / NATIVE / LLM_POINTWISE / LLM_LISTWISE）、原生服务 ID、LLM 服务 ID、知识 TopK、记忆开关和 TopK

### 2.4 多 Provider 支持

支持 4 种 LLM Provider 类型（`ProviderType` 枚举）：

- **Ollama**（`ollama`）：本地部署
- **HuggingFace TEI**（`tei`）：向量化和精排专用
- **OpenAI 兼容**（`openai-compatible`）：覆盖 DeepSeek、通义千问、智谱 GLM、文心一言、Gemini 等主流厂商
- **Anthropic**（`anthropic`）：Claude API

TEI 和 OpenAI 兼容类型底层共用 OpenAI 兼容 API 协议。

### 2.5 场景路由

定义 7 种调用场景（`LlmScene` 常量）：

- `chat`：通用对话
- `agent_react`：ReAct Agent 循环
- `knowledge_extraction`：知识实体提取
- `memory_compression`：记忆压缩
- `embedding`：向量化
- `skill_generation`：Skill 自动生成
- `knowledge_rerank`：知识精排

每个模型服务声明自己支持的场景列表，路由器优先选择场景匹配的服务，未声明场景的通用服务作为兜底。生成路由还支持通过 `GenerationSettingsEntity.sceneServiceBindings` 将场景硬绑定到指定服务。

### 2.6 生成子能力

生成类服务声明 `GenerationCapability` 子能力集合：

- `CHAT`：对话生成
- `STRUCTURED_OUTPUT`：结构化输出（JSON Schema）
- `FUNCTION_CALLING`：函数调用 / 工具使用
- `STREAMING`：流式输出
- `VISION`：视觉理解（图片输入）
- `NATIVE_AUDIO`：原生音频理解（如 Qwen3-Omni）
- `NATIVE_VIDEO`：原生视频理解（如 Gemini File API）

路由器根据调用需求自动筛选具备对应子能力的服务。

### 2.7 熔断器与故障转移

每个路由器共享 `CircuitBreakerManager`，以 `serviceId:capabilityType` 复合键隔离管理熔断器：

- **三态模型**：Closed（正常）→ Open（熔断）→ HalfOpen（探测恢复）
- **自动故障转移**：当某服务熔断时自动跳过，尝试下一个候选
- **持久化恢复**：熔断器状态异步持久化到 `circuit_breaker_states` 表，重启后自动恢复
- **陈旧清理**：`purgeStaleBreakers()` 清理已删除服务的残留熔断器记录

熔断器位于 `com.lifepilot.llm.circuit` 包。

### 2.8 指数退避重试

`ExponentialBackoff` 类实现指数退避策略：

- 初始延迟：500ms
- 退避倍数：2.0
- 最大延迟：5s
- 最大重试次数：2 次

避免对故障 Provider 造成额外压力。MultimodalRouter 在多模态故障转移时使用。

### 2.9 语义缓存

基于 sqlite-vec 向量相似度的语义缓存（`com.lifepilot.llm.cache.SemanticCache`）。按 `scene + agentPhase + responseFormatKey` 三维隔离，确保结构化输出与自由文本不混缓存。

- **GenerationRouter 自动集成**：`CHAT` 和 `STRUCTURED_OUTPUT` 调用自动查询/写入缓存
- **延迟初始化**：首次使用时通过 `EmbeddingRouter` 探测维度并创建 vec0 虚拟表
- **淘汰策略**：TTL 过期 + LRU 超限淘汰
- **容错降级**：所有数据库操作异常 catch 后静默降级

### 2.10 Provider 健康检查

`ProviderHealthChecker` 使用 Virtual Thread 并行检查所有 Provider 的健康状态：

- 单个 Provider 超时 10 秒标记为不健康
- 返回不可变的健康状态映射，供管理界面展示

### 2.11 精排多策略

RerankRouter 支持三种精排策略（通过 `RerankExecutionMode` 配置）：

- **NATIVE**：调用原生精排 API（如 HuggingFace TEI rerank），延迟低，需要专用模型
- **LLM_POINTWISE**：使用 LLM 对每个候选逐条评分，精度高但 Token 消耗大
- **LLM_LISTWISE**：使用 LLM 对全部候选批量排序，Token 消耗适中

精排同时支持知识文档和记忆条目两种场景，分别通过 `rerankDocuments()` 和 `rerankMemoryCandidates()` 调用。

### 2.12 多模态路由

MultimodalRouter 处理包含媒体附件的请求，支持渐进降级策略：

- **图片**：VISION 能力 Provider 直接调用，内置图片预处理缓存（LRU 50 条，10 分钟 TTL）
- **音频**：优先原生音频路由（NATIVE_AUDIO Provider），失败回退默认 STT 转录流程
- **视频**：优先原生视频路由（Gemini File API 上传 + NATIVE_VIDEO Provider），失败回退关键帧分治（VideoProcessor 提取关键帧转为图片列表）
- **纯文本**：自动委托 GenerationRouter，无额外开销

### 2.13 流式响应

GenerationRouter 提供 `streamWithInfo()` 返回 `StreamingGenerationResponse`（含 `Flux<String>` + 服务元信息），MultimodalRouter 提供 `streamWithInfo()` 返回 `StreamingLlmResponse`，适用于 Web UI 的 SSE 实时输出。

## 3. 使用场景

用户通过前端管理界面或 API 注册多个模型服务（如一个本地 Ollama 用于日常对话，一个 DeepSeek 用于复杂推理，一个 TEI 用于向量化和精排）。可使用厂商模板一键创建服务实例，并通过 Per-Capability 设置为不同场景和用途绑定最优服务。

系统启动后自动加载所有已启用服务。Agent 引擎调用 GenerationRouter 时根据场景自动选择最优服务，知识库通过 EmbeddingRouter 进行向量化，搜索结果通过 RerankRouter 精排。当某个服务中断时，系统自动切换到备选服务，用户无感知。

## 4. 配置项

### 4.1 模型服务（DB 管理）

通过 `model_services` 表管理，核心字段：

| 字段 | 说明 |
|------|------|
| `id` | 服务唯一标识 |
| `kind` | 服务类型：GENERATION / EMBEDDING / RERANK |
| `provider_type` | Provider 类型：ollama / tei / openai-compatible / anthropic |
| `api_url` | API 基础 URL |
| `api_key` | API 密钥（加密存储） |
| `model_name` | 模型名称 |
| `timeout_seconds` | 超时秒数（默认 30） |
| `priority` | 优先级（数值越小越高） |
| `enabled` | 是否启用 |
| `supported_scenes` | 支持的场景列表 |
| `generation_capabilities` | 生成子能力集合 |

### 4.2 Per-Capability 设置（DB 管理）

| 设置 | 配置项 | 说明 |
|------|--------|------|
| 生成设置 | `defaultServiceId` | 默认生成服务 |
| 生成设置 | `sceneServiceBindings` | 场景 → 服务绑定映射 |
| 向量化设置 | `defaultServiceId` | 默认向量化服务 |
| 向量化设置 | `knowledgeBaseServiceId` | 知识库专用向量化服务 |
| 向量化设置 | `memoryServiceId` | 记忆专用向量化服务 |
| 精排设置 | `enabled` | 精排总开关 |
| 精排设置 | `mode` | 精排模式：DISABLED / NATIVE / LLM_POINTWISE / LLM_LISTWISE |
| 精排设置 | `nativeServiceId` | 原生精排服务 ID |
| 精排设置 | `llmServiceId` | LLM 精排服务 ID |
| 精排设置 | `knowledgeTopK` | 知识精排返回条数（默认 5） |
| 精排设置 | `memoryEnabled` | 记忆精排开关 |
| 精排设置 | `memoryTopK` | 记忆精排返回条数（默认 10） |

### 4.3 熔断器（application.yml）

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.llm.circuit-breaker.failure-threshold` | 3 | 触发熔断的连续失败阈值 |
| `lifepilot.llm.circuit-breaker.reset-timeout-seconds` | 60 | OPEN 状态恢复超时（秒） |
| `lifepilot.llm.circuit-breaker.half-open-max-attempts` | 1 | HALF_OPEN 最大探测次数 |

### 4.4 语义缓存（application.yml）

| 配置键 | 说明 |
|--------|------|
| `lifepilot.llm.cache.similarity-threshold` | 向量相似度命中阈值 |
| `lifepilot.llm.cache.ttl-seconds` | 缓存条目 TTL（秒） |
| `lifepilot.llm.cache.max-entries` | 缓存最大条目数 |

## 5. 限制与未来方向

- 当前语义缓存依赖 sqlite-vec，大规模缓存场景下性能待评估
- 暂不支持 Provider 级别的 Token 用量统计和成本控制面板
- MultimodalRouter 的 VISION 路由仍依赖 YAML 驱动的 ProviderRegistry，后续计划迁移到 ModelServiceRegistry
- 未来计划：引入 Token 用量追踪，优化首字响应时间（TTFT），支持更多精排模型
