# LLM 路由 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.llm`
> **最后更新**：2026-03

## 1. 功能概述

LLM Router 为知微提供统一的大模型调用能力，支持多 Provider 动态路由、自动故障转移和语义缓存。用户只需配置 Provider 信息，系统自动根据调用场景选择最优模型，并在 Provider 故障时无感切换到备选方案。

## 2. 核心特性

### 2.1 多 Provider 支持

支持 7 种 LLM Provider 类型：Ollama（本地）、DeepSeek、通义千问、智谱 GLM、文心一言、HuggingFace TEI、OpenAI 兼容 API。通过 `application.yml` 配置即可接入，无需修改代码。

### 2.2 场景路由

定义 8 种调用场景（`LlmScene` 常量）：

- `chat`: 通用对话
- `agent_react`: ReAct Agent 循环
- `knowledge_extraction`: 知识实体提取（AUDN）
- `memory_compression`: 记忆压缩
- `embedding`: 向量嵌入
- `proactive_reasoning`: ~~已删除（主动推理模块已废弃）~~
- `skill_generation`: Skill 自动生成
- `knowledge_rerank`: 知识精排

每个 Provider 声明自己支持的场景，路由层自动匹配。不同场景可使用不同模型。

### 2.3 熔断器与故障转移

每个 Provider 独立维护熔断器状态（Closed → Open → HalfOpen）。当某个 Provider 连续失败达到阈值时自动熔断，请求自动转移到下一个可用 Provider。恢复后自动探测并重新启用。

熔断器位于 `com.lifepilot.llm.circuit` 包。

### 2.4 指数退避重试

`ExponentialBackoff` 类实现指数退避策略：

- 初始延迟：500ms
- 退避倍数：2.0
- 最大延迟：5s
- 最大重试次数：2 次

避免对故障 Provider 造成额外压力。

### 2.5 语义缓存

基于 sqlite-vec 向量相似度的语义缓存（`com.lifepilot.llm.cache` 包）。对于语义相近的 Prompt，直接返回缓存结果，减少 LLM 调用次数和 Token 消耗。

### 2.6 多能力声明

Provider 可声明 9 种能力（`ProviderCapability` 枚举）：

- `CHAT`: 对话能力
- `EMBEDDING`: 向量嵌入
- `STRUCTURED_OUTPUT`: 结构化输出
- `FUNCTION_CALLING`: 函数调用
- `STREAMING`: 流式输出
- `VISION`: 视觉理解
- `TTS`: 文字转语音
- `STT`: 语音转文字
- `RERANK`: 重排序

路由层根据调用需求自动筛选具备对应能力的 Provider。

### 2.7 流式响应

支持 `stream()` 和 `streamWithInfo()` 流式调用，返回 `Flux<String>` 或 `StreamingLlmResponse`，适用于 Web UI 的 SSE 实时输出。

## 3. 使用场景

用户在 `application.yml` 中配置多个 LLM Provider（如一个本地 Ollama 用于日常对话，一个 DeepSeek 用于复杂推理，一个 TEI 用于向量嵌入）。系统启动后自动注册所有 Provider，Agent 引擎调用时根据场景自动选择最优 Provider。当某个 Provider 服务中断时，系统自动切换到备选 Provider，用户无感知。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.llm.providers.{id}.type` | — | Provider 类型 |
| `lifepilot.llm.providers.{id}.base-url` | — | API 基础 URL |
| `lifepilot.llm.providers.{id}.api-key` | — | API 密钥 |
| `lifepilot.llm.providers.{id}.model` | — | 模型名称 |
| `lifepilot.llm.providers.{id}.scenes` | — | 支持的场景列表 |
| `lifepilot.llm.providers.{id}.capabilities` | — | 能力声明 |
| `lifepilot.llm.providers.{id}.priority` | — | 优先级（数值越小优先级越高） |
| `lifepilot.llm.circuit-breaker.failure-threshold` | — | 熔断失败阈值 |
| `lifepilot.llm.circuit-breaker.recovery-timeout` | — | 熔断恢复超时 |

## 5. 限制与未来方向

- 当前语义缓存依赖 sqlite-vec，大规模缓存场景下性能待评估
- 暂不支持 Provider 级别的 Token 用量统计和成本控制面板
- 未来计划：引入缓存机制进一步减少 Token 消耗，缩短首字响应时间（TTFT）
