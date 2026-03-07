# 性能优化架构设计：缓存机制与 TTFT 优化

> **文档性质**：深度架构设计文档（Developer-Facing）
> **目标读者**：核心开发者、架构评审者
> **模块归属**：`com.lifepilot.llm`（缓存层）+ `com.lifepilot.agent`（TTFT 优化）
> **最后更新**：2026-03
> **从属关系**：本文档为模块依赖图 §26 的调研产出，覆盖两个优化方向

---

## 目录

- [1. 问题域分析](#1-问题域分析)
- [2. 前沿理论研究](#2-前沿理论研究)
- [3. 优秀开源项目分析](#3-优秀开源项目分析)
- [4. 竞品深度分析](#4-竞品深度分析)
- [5. 优化方向一：多层缓存机制](#5-优化方向一多层缓存机制)
- [6. 优化方向二：TTFT 优化](#6-优化方向二ttft-优化)
- [7. LifePilot 适配方案](#7-lifepilot-适配方案)
- [8. 设计决策与权衡](#8-设计决策与权衡)
- [9. 调研参考汇总](#9-调研参考汇总)

---

## 1. 问题域分析

### 1.1 当前瓶颈

LifePilot 作为个人 AI Agent，每次用户交互都会触发 AgentLoop 控制循环，循环中的每一步都涉及 LLM 调用。当前存在两个核心性能问题：

**问题 1：Token 消耗冗余**

Agent 循环中存在大量重复的 Token 消耗：
- System Prompt 在每轮循环中重复发送（约 800-1500 Token）
- 相同或语义相似的用户查询重复调用 LLM
- 工具调用结果在多步任务中重复传递
- 记忆检索结果在同一会话内重复嵌入上下文
- ContextAssembler 每轮重新组装完整上下文，包含大量不变的静态片段

**问题 2：首字响应时间（TTFT）过长**

用户发送消息到看到第一个字符的延迟链路：

```
用户消息 → WebController 接收
  → L1 写入用户消息（~5ms）
  → chat_messages 持久化（~10ms）
  → SSE traceStart + reasoningEvent 发送（~2ms）
  → TraceContext 初始化（~3ms）
  → coreLoop 第一轮迭代开始
    → ContextAssembler.assemble()
      → HybridRetriever 三路检索（向量 + FTS5 + 图遍历，~50-200ms）
      → 知识库检索（~30-100ms）
      → L2 跨会话检索（~20-50ms）
      → L1 会话历史读取（~5ms）
      → TokenBudgetAllocator 预算分配（~1ms）
      → 截断 + 格式化 + Prompt 构建（~5ms）
    → LLM 调用（TTFT ~200-2000ms，取决于 Provider）
  → 第一个 Token 到达用户
```

总延迟约 330-2400ms，其中：
- 上下文组装占 110-360ms（可优化）
- LLM Provider TTFT 占 200-2000ms（部分可优化）

### 1.2 当前架构基线

**已实现的缓存机制：**

| 层级 | 机制 | 位置 | 状态 |
|------|------|------|------|
| 请求级检索缓存 | `ContextAssembler.retrievalCache` | `ConcurrentHashMap` 按 traceId+query+topK 去重 | ✅ 已实现 |
| 熔断器状态缓存 | `CircuitBreakerManager` | Provider 级别健康状态 | ✅ 已实现 |
| SemanticCache | `LlmRouter` 前置拦截 | 架构文档已设计（llm-router.md §7.6），代码未实现 | ❌ 缺失 |
| Prompt 前缀缓存 | Provider 侧 KV Cache | 依赖 Provider 支持 | ❌ 未利用 |
| 工具结果缓存 | 无 | — | ❌ 缺失 |

**已实现的流式能力：**

- `AgentLoop.runStreaming()` 支持 SSE 流式输出
- `LlmRouter.stream()` / `streamWithInfo()` 支持流式 LLM 调用
- 前端 SSE 接收 token + ui 事件交替传输

**关键缺口：**

1. `SemanticCache` 在 `docs/architecture/llm-router.md` 中有完整设计（§7.6），`LlmAutoConfiguration` 中有 `@Bean` 声明，但实际类未实现
2. `LlmRouter.call()` 当前直接走 Provider 故障转移循环，无缓存拦截层
3. ContextAssembler 的三路检索（向量 + FTS5 + 图遍历）是串行执行，未并行化
4. 未利用 Provider 侧的 Prefix Caching 能力（DeepSeek / Qwen / Ollama 均支持）
5. System Prompt 每轮重复发送，未做结构化前缀优化

---

## 2. 前沿理论研究

### 2.1 Prompt Caching 与 KV Cache 复用

**核心论文与研究：**

Nizar et al. (2026) 的综述研究 "Prompt Caching in Large Language Models" 系统性地分析了 Prompt Caching 的理论基础和实践效果。核心发现：

- Prompt Caching 可降低 API 成本 45-80%，TTFT 降低 13-31%
- 缓存命中率与 Prompt 前缀稳定性高度相关：前缀越长且越稳定，命中率越高
- 对于 Agent 场景，System Prompt + 工具定义构成天然的稳定前缀（占总 Token 的 30-50%）

**KV Cache 复用机制：**

LLM 推理过程中，Transformer 的 Key-Value Cache 是计算密集型操作的核心产物。KV Cache 复用的核心思想是：如果两次请求共享相同的 Prompt 前缀，则前缀部分的 KV Cache 可以直接复用，跳过重复计算。

相关研究进展：
- **KVFlow** (2025)：提出跨请求的 KV Cache 流式传输机制，在分布式推理场景下实现 KV Cache 的高效共享
- **LayerKV** (2025)：提出分层 KV Cache 量化策略，在保持精度的同时将 KV Cache 内存占用降低 40-60%
- **Strata** (2025)：提出层次化上下文缓存（Hierarchical Context Caching），将 Prompt 分为多个语义层级，每层独立缓存和失效

**对 LifePilot 的启示：**

LifePilot 的 Agent 循环天然具备高前缀复用率——System Prompt（角色定义 + 工具声明 + 行为约束）在同一会话的多轮循环中完全不变。通过将 Prompt 结构化为「稳定前缀 + 动态后缀」，可以最大化 Provider 侧 KV Cache 命中率。

### 2.2 语义缓存（Semantic Caching）

**SCALM 论文 (2025)：**

SCALM (Semantic Caching for Automated LLM Management) 提出了一种基于语义相似度的 LLM 响应缓存框架，核心贡献：

- 双阈值机制：高阈值（>0.95）直接返回缓存，中阈值（0.85-0.95）返回缓存但标记为"近似"
- 缓存命中率提升 63%（相比精确匹配）
- 引入 Cache Confidence Score，量化缓存响应的可信度

**语义缓存的关键挑战：**

1. **相似度阈值选择**：阈值过低导致错误命中（返回不相关响应），阈值过高导致命中率过低
2. **Embedding 质量**：缓存效果高度依赖 Embedding 模型的语义区分能力
3. **缓存失效策略**：LLM 响应可能因上下文变化而过时，需要智能失效机制
4. **Agent 场景特殊性**：Agent 的同一查询在不同状态下可能需要不同响应（如不同的 AgentPhase）

**对 LifePilot 的启示：**

LifePilot 的 `LlmConfigProperties.CacheConfig` 已定义 `similarityThreshold=0.92`，这是一个合理的起点。但需要引入 scene 维度的缓存隔离——不同场景（PLANNING / EXECUTING / RESPONDING）的缓存不应互相污染。

### 2.3 Agent 缓存的五层模型

fast.io (2025) 提出了 Agent 缓存的五层架构模型，从快到慢：

| 层级 | 名称 | 命中条件 | 延迟 | 节省 |
|------|------|---------|------|------|
| L1 | Prompt Caching | 相同前缀 | ~0ms（Provider 侧） | 50-90% 输入 Token |
| L2 | Exact Match Cache | 完全相同的请求 | <1ms | 100% Token |
| L3 | Semantic Cache | 语义相似度 >= 阈值 | ~5-20ms（Embedding 计算） | 100% Token |
| L4 | Tool Result Cache | 相同工具 + 相同参数 | <1ms | 避免重复工具执行 |
| L5 | RAG Chunk Cache | 相同查询的检索结果 | <5ms | 避免重复向量检索 |

**对 LifePilot 的启示：**

LifePilot 当前仅实现了 L5 的部分能力（`ContextAssembler.retrievalCache`）。五层模型中，L1（Prompt Caching）和 L3（Semantic Cache）对 LifePilot 的收益最大：
- L1 无需应用层改动，通过 Prompt 结构优化即可激活 Provider 侧能力
- L3 已有架构设计（`SemanticCache`），需要落地实现
- L2 作为 L3 的特例（相似度=1.0），可以合并到 L3 实现中
- L4 对多步工具调用场景有价值，但优先级低于 L1/L3

### 2.4 TTFT 优化理论

**Prompt 压缩：**

LLMLingua (Microsoft, 2024) 提出了基于 Token 级别的 Prompt 压缩技术，可在保持语义的前提下压缩 Prompt 20-40%。但该方法需要额外的小模型推理，引入新的延迟。对于 LifePilot 的单用户场景，Prompt 压缩的收益不如 Prompt 结构优化（前缀稳定化）。

**并行上下文组装：**

ContextAssembler 当前的检索流程是串行的：HybridRetriever → 知识库检索 → L2 跨会话检索。这三个检索操作之间无数据依赖，可以并行执行。理论上可将检索延迟从 sum(各检索延迟) 降低到 max(各检索延迟)，预计节省 30-60% 的上下文组装时间。

**流式优先架构：**

传统的请求-响应模式要求完整生成响应后才返回。流式架构允许 LLM 生成第一个 Token 后立即开始传输，用户感知的 TTFT 等于 LLM 的实际 TTFT，而非 LLM TTFT + 完整生成时间。LifePilot 已实现流式架构（`AgentLoop.runStreaming`），但上下文组装阶段仍是阻塞的。

### 2.5 Token 消耗优化研究

业界研究表明，典型 LLM 应用中 40-60% 的 Token 消耗是冗余的（重复的 System Prompt、冗长的上下文、未压缩的工具输出）。优化策略按效果排序：

1. **Prompt 前缀缓存**（Provider 侧）：节省 50-90% 输入 Token 成本，零应用层改动
2. **语义缓存**（应用层）：对重复/相似查询节省 100% Token，需要 Embedding 计算开销
3. **上下文压缩**（应用层）：节省 20-40% Token，需要额外推理开销
4. **工具输出摘要**（应用层）：对长工具输出节省 50-80% Token，需要额外 LLM 调用

对 LifePilot 而言，策略 1 和 2 的 ROI 最高，策略 3 和 4 可作为后续优化。

---

## 3. 优秀开源项目分析

### 3.1 GPTCache（Zilliz）

**项目概况：** GitHub Stars 7k+，Python 实现，专注于 LLM 响应缓存。

**架构设计：**
- 模块化管道：Pre-processor → Embedding → Cache Store → Similarity Evaluator → Post-processor
- 支持多种 Embedding 后端（OpenAI / Hugging Face / ONNX）
- 支持多种向量存储（Milvus / FAISS / ChromaDB / SQLite）
- 支持多种相似度评估策略（余弦相似度 / 欧氏距离 / ONNX 模型评估）

**核心抽象：**

`
CacheBase (接口)
  ├── SQLiteCache      — 本地 SQLite 存储
  ├── MilvusCache      — Milvus 向量数据库
  └── RedisCache       — Redis 缓存

SimilarityEvaluator (接口)
  ├── ExactMatchEvaluator    — 精确匹配
  ├── OnnxModelEvaluator     — ONNX 模型评估
  └── CosineDistanceEvaluator — 余弦距离
`

**可借鉴的设计：**
- 管道化架构：每个阶段可独立替换，便于测试和扩展
- 缓存键不仅包含 query，还包含 model + temperature 等参数，避免跨模型污染
- 支持 session 级别的缓存隔离

**局限性：**
- Python 生态，无法直接用于 Java/Spring Boot
- 缺乏 Agent 场景的特殊处理（如 AgentPhase 感知）
- 缓存失效策略较简单（TTL 为主）

### 3.2 LMCache

**项目概况：** 专注于 KV Cache 层面的优化，面向推理引擎（vLLM / SGLang）。

**核心能力：**
- KV Cache 的跨请求共享和持久化
- 支持 CPU 内存 / GPU 内存 / 磁盘三级存储
- 与 vLLM 深度集成，支持 Prefix Caching

**对 LifePilot 的参考价值：**
- LMCache 的优化发生在推理引擎层，LifePilot 作为 API 调用方无法直接使用
- 但其「稳定前缀最大化」的设计思想可以指导 LifePilot 的 Prompt 结构优化
- 验证了 KV Cache 复用对 TTFT 的显著改善（长上下文场景下 TTFT 降低 3-10x）

### 3.3 Redis LangCache

**项目概况：** Redis 官方的 LLM 语义缓存方案，基于 Redis Stack 的向量搜索能力。

**架构特点：**
- 利用 Redis 的 RediSearch 模块进行向量相似度搜索
- 支持 HNSW 和 FLAT 两种向量索引
- 缓存条目包含：query embedding + response + metadata + TTL

**可借鉴的设计：**
- 缓存条目的 metadata 设计：包含 model、temperature、timestamp、hit_count
- 基于 hit_count 的热度感知淘汰策略
- 支持 namespace 隔离（类似 LifePilot 的 scene 隔离）

**局限性：**
- 依赖 Redis Stack，增加部署复杂度（LifePilot 追求单 JAR 部署）
- 向量搜索性能依赖 Redis 内存，不适合资源受限的个人部署

### 3.4 Spring AI 缓存生态

**Spring AI 1.1.x 的缓存支持：**
- `ChatClient` 支持 `Advisor` 链，可以在 Advisor 中实现缓存拦截
- `CachingAdvisor`（社区贡献）：基于 Spring Cache 抽象的简单缓存
- 无内置的语义缓存支持

**对 LifePilot 的参考价值：**
- LifePilot 的 `LlmRouter` 不直接使用 `ChatClient.Advisor` 链（因为路由层在 Advisor 之下）
- 但 Advisor 模式的「横切关注点注入」思想可以借鉴：缓存作为 LlmRouter 的前置拦截层
- Spring Cache 抽象（`@Cacheable`）不适合语义缓存（需要向量相似度计算），需要自定义实现

### 3.5 对比矩阵

| 维度 | GPTCache | LMCache | Redis LangCache | Spring AI |
|------|----------|---------|-----------------|-----------|
| 缓存层级 | 应用层语义缓存 | 推理引擎 KV Cache | 应用层语义缓存 | 应用层精确缓存 |
| 向量存储 | 多种（Milvus/FAISS/SQLite） | N/A（KV Cache） | Redis RediSearch | N/A |
| 部署复杂度 | 中（Python + 向量DB） | 高（推理引擎集成） | 中（Redis Stack） | 低（Spring 原生） |
| Agent 感知 | 无 | 无 | 无 | 无 |
| 适合 LifePilot | 架构参考 | 思想参考 | 架构参考 | 集成参考 |

**结论：** 没有现成方案可以直接用于 LifePilot。最佳策略是借鉴 GPTCache 的管道化架构 + Redis LangCache 的 metadata 设计，基于 LifePilot 已有的 SQLite + sqlite-vec 基础设施实现自定义 `SemanticCache`（已有架构设计，需落地）。

---

## 4. 竞品深度分析

### 4.1 DeepSeek Context Caching（磁盘缓存）

**机制：** DeepSeek 的 Context Caching 是自动触发的磁盘级 KV Cache 持久化，无需 API 调用方做任何改动。

**关键特性：**
- 自动缓存：当请求的 Prompt 前缀与之前的请求匹配时，自动复用磁盘上的 KV Cache
- 成本降低 90%：缓存命中的 Token 按 0.1x 价格计费
- TTFT 大幅降低：128K 上下文场景下，TTFT 从 ~13s 降至 ~500ms
- 缓存粒度：以 64 Token 为单位对齐，前缀匹配到最近的 64 Token 边界
- 缓存有效期：最后一次命中后 ~30 分钟过期（冷启动后需要一次完整计算）

**对 LifePilot 的启示：**
- DeepSeek 是 LifePilot 的主要云端 Provider，其自动缓存机制可以「免费」获得
- 关键优化点：确保 Prompt 前缀稳定（System Prompt + 工具定义放在最前面）
- 64 Token 对齐意味着 Prompt 结构的微小变化可能导致缓存失效

### 4.2 Qwen Context Cache（阿里通义）

**机制：** Qwen 提供三种缓存模式：

| 模式 | 触发方式 | 适用场景 |
|------|---------|---------|
| 隐式缓存 | 自动（前缀匹配） | 通用场景，类似 DeepSeek |
| 显式缓存 | API 参数 `enable_context_cache=true` | 需要精确控制缓存行为 |
| 会话缓存 | 基于 session_id | 多轮对话场景 |

**关键特性：**
- 隐式缓存：前缀匹配，128 Token 对齐粒度
- 显式缓存：支持指定缓存的 Token 范围，更精确的缓存控制
- 成本降低：缓存命中部分按 0.1x 计费
- 缓存有效期：隐式缓存 ~10 分钟，显式缓存可配置更长

**对 LifePilot 的启示：**
- Qwen 的会话缓存模式与 LifePilot 的 session 概念天然对齐
- 可以在 `ProviderAdapter` 层为 Qwen 启用 `enable_context_cache` 参数
- 128 Token 对齐粒度比 DeepSeek 的 64 Token 更粗，对 Prompt 结构变化更敏感

### 4.3 Anthropic Prompt Caching

**机制：** Anthropic 的 Prompt Caching 需要显式标记缓存断点（`cache_control` 参数）。

**关键特性：**
- 显式缓存：在消息中插入 `cache_control: {type: "ephemeral"}` 标记缓存断点
- 成本模型：缓存写入 1.25x，缓存读取 0.1x（首次写入略贵，后续读取大幅便宜）
- TTFT 降低 85%：缓存命中时跳过前缀的 KV 计算
- 最小缓存长度：1024 Token（短 Prompt 无法缓存）
- 缓存有效期：5 分钟（每次命中刷新）

**对 LifePilot 的启示：**
- Anthropic 的显式缓存模式需要在 `ProviderAdapter` 层注入 `cache_control` 参数
- 1024 Token 最小长度对 LifePilot 不是问题（System Prompt + 工具定义通常 > 1500 Token）
- 缓存写入的 1.25x 成本意味着首次调用略贵，但后续调用大幅便宜

### 4.4 OpenAI Automatic Caching

**机制：** OpenAI 的 Prompt Caching 是完全自动的，无需 API 调用方做任何改动。

**关键特性：**
- 自动缓存：1024 Token 以上的前缀自动缓存
- 成本降低 50%：缓存命中的 Token 按 0.5x 计费（比 DeepSeek/Qwen 的 0.1x 贵）
- 缓存粒度：128 Token 对齐
- 缓存有效期：5-10 分钟

**对 LifePilot 的启示：**
- OpenAI 当前不是 LifePilot 的 Provider，但如果未来接入，其自动缓存可以直接受益
- 0.5x 的折扣率低于 DeepSeek/Qwen 的 0.1x，成本优势较小

### 4.5 Ollama（本地推理）

**机制：** Ollama 内置 KV Cache 管理，同一模型实例的连续请求自动复用 KV Cache。

**关键特性：**
- 自动 KV Cache：同一模型实例的请求自动复用前缀 KV Cache
- 无成本影响：本地推理无 Token 计费
- TTFT 优化：KV Cache 命中时 TTFT 显著降低
- 缓存有效期：模型卸载前一直有效

**对 LifePilot 的启示：**
- Ollama 是 LifePilot 的优先 Provider（priority=0），其 KV Cache 复用是「免费」的
- 关键优化：保持 Prompt 前缀稳定，避免不必要的前缀变化导致 KV Cache 失效
- Ollama 的 `/api/chat` 接口支持 `keep_alive` 参数控制模型驻留时间

### 4.6 竞品对比总结

| Provider | 缓存类型 | 触发方式 | 成本折扣 | 最小长度 | 对齐粒度 | 有效期 |
|----------|---------|---------|---------|---------|---------|--------|
| DeepSeek | 磁盘 KV Cache | 自动 | 0.1x | 无限制 | 64 Token | ~30min |
| Qwen | 内存/磁盘 KV Cache | 自动/显式/会话 | 0.1x | 无限制 | 128 Token | 10-30min |
| Anthropic | 内存 KV Cache | 显式标记 | 0.1x（读）/1.25x（写） | 1024 Token | N/A | 5min |
| OpenAI | 内存 KV Cache | 自动 | 0.5x | 1024 Token | 128 Token | 5-10min |
| Ollama | 内存 KV Cache | 自动 | N/A（本地） | 无限制 | N/A | 模型驻留期 |

**核心结论：** LifePilot 的三个 Provider（Ollama / DeepSeek / Qwen）都支持自动 Prefix Caching，且 DeepSeek 和 Qwen 的折扣率高达 90%。LifePilot 无需实现 Provider 侧的缓存逻辑，只需优化 Prompt 结构以最大化前缀稳定性。

---

## 5. 优化方向一：多层缓存机制

基于前沿理论（§2.3 五层模型）和竞品分析（§4），为 LifePilot 设计三层缓存架构：

### 5.1 三层缓存架构

`
请求入口（LlmRouter.call / stream）
  │
  ├─ L1: Provider Prefix Caching（Provider 侧，零改动）
  │     ├─ 通过 Prompt 结构优化激活
  │     ├─ System Prompt + 工具定义 = 稳定前缀
  │     └─ 节省 50-90% 输入 Token 成本 + 降低 TTFT
  │
  ├─ L2: SemanticCache（应用层，需实现）
  │     ├─ 基于 sqlite-vec 向量相似度匹配
  │     ├─ scene 维度隔离 + TTL 失效
  │     ├─ 命中时直接返回缓存响应，节省 100% Token
  │     └─ 已有架构设计（llm-router.md §7.6），需落地
  │
  └─ L3: Tool Result Cache（应用层，可选）
        ├─ 相同工具 + 相同参数 = 缓存命中
        ├─ 适用于幂等工具（查询类）
        └─ 非幂等工具（写入类）不缓存
`

### 5.2 L1: Provider Prefix Caching 优化

**原理：** 通过优化 Prompt 结构，使 System Prompt + 工具定义构成稳定的前缀，最大化 Provider 侧 KV Cache 命中率。

**当前 Prompt 结构（ContextAssembler 输出）：**

`
[System Prompt]          ← 每轮不变（~800-1500 Token）
[User Prompt]
  ├─ 用户画像             ← 会话内不变
  ├─ 相关记忆             ← 每轮可能变化
  ├─ 知识库片段           ← 每轮可能变化
  ├─ 跨会话片段           ← 每轮可能变化
  ├─ 会话历史             ← 每轮递增
  └─ 当前用户请求         ← 每轮变化
`

**优化后的 Prompt 结构：**

`
[System Prompt]          ← 稳定前缀区（Provider KV Cache 命中）
  ├─ 角色定义
  ├─ 行为约束
  └─ 工具定义（Function Calling Schema）
[User Prompt - 半稳定区]
  ├─ 用户画像             ← 会话内不变
  └─ 会话历史（压缩版）   ← 缓慢递增
[User Prompt - 动态区]
  ├─ 相关记忆
  ├─ 知识库片段
  ├─ 跨会话片段
  └─ 当前用户请求
`

**关键设计决策：**
- 将工具定义从 User Prompt 移到 System Prompt 末尾（如果当前不在 System Prompt 中）
- 用户画像放在动态内容之前，利用其会话内不变的特性扩展稳定前缀
- 会话历史使用 DialogCompressor 压缩后放在半稳定区

**预期收益：**
- Ollama：KV Cache 命中率从不确定提升到 ~80%（稳定前缀占比）
- DeepSeek：缓存命中的 Token 按 0.1x 计费，预计节省 40-60% 输入成本
- Qwen：类似 DeepSeek，预计节省 40-60% 输入成本

### 5.3 L2: SemanticCache 实现

**已有设计基础：** `docs/architecture/llm-router.md` §7.6 已有完整的 `SemanticCache` 设计，包括：
- 基于 sqlite-vec 的向量存储
- 余弦相似度 >= 0.92 判定命中
- scene 维度隔离
- TTL 失效 + LRU 淘汰
- `LlmAutoConfiguration` 中已有 `@Bean` 声明

**需要补充的设计：**

1. **AgentPhase 感知**：同一查询在 PLANNING / EXECUTING / RESPONDING 阶段可能需要不同响应，缓存键应包含 phase 信息
2. **缓存预热**：对高频查询（如 "今天有什么安排"）可以在空闲时预热缓存
3. **缓存统计**：命中率、平均相似度、缓存大小等指标，接入可观测性系统
4. **流式响应缓存**：`LlmRouter.stream()` 的缓存需要特殊处理（缓存完整响应，命中时模拟流式返回）

**缓存键设计：**

`
CacheKey = hash(scene + phase + normalize(prompt))
`

其中 `normalize(prompt)` 去除时间戳、session ID 等动态部分，保留语义核心。

### 5.4 L3: Tool Result Cache（可选）

**适用场景：**
- 查询类工具：`todo.list`、`schedule.today`、`habit.status`
- 短时间内（如 5 分钟）相同参数的重复调用

**不适用场景：**
- 写入类工具：`todo.add`、`schedule.create`
- 参数包含时间戳或随机值的调用

**实现方式：**
- 在 `GuardrailEngine` 或工具执行层添加缓存拦截
- 缓存键 = 工具 ID + 参数哈希
- TTL 较短（1-5 分钟），避免数据过时

**优先级：** 低。L1 和 L2 的收益远大于 L3，L3 作为后续优化。

---

## 6. 优化方向二：TTFT 优化

### 6.1 TTFT 延迟分解

基于 §1.1 的延迟链路分析，TTFT 可分解为三个阶段：

| 阶段 | 耗时 | 可优化性 |
|------|------|---------|
| 前置处理（L1 写入 + 持久化 + SSE 初始化） | ~20ms | 低（已足够快） |
| 上下文组装（ContextAssembler.assemble） | 110-360ms | 高（并行化 + 缓存） |
| LLM Provider TTFT | 200-2000ms | 中（Prefix Caching + Provider 选择） |

### 6.2 上下文组装并行化

**当前串行流程：**

```
HybridRetriever.retrieve()     ~50-200ms
  ↓
safeRetrieveKnowledgeBaseSnippets()  ~30-100ms
  ↓
safeSearchCrossSession()       ~20-50ms
  ↓
safeGetSessionHistory()        ~5ms
  ↓
safeAllocate() + 截断 + 格式化  ~6ms
```

**优化后并行流程：**

```
┌─ HybridRetriever.retrieve()        ~50-200ms ─┐
├─ safeRetrieveKnowledgeBaseSnippets() ~30-100ms ─┤ max() = 50-200ms
├─ safeSearchCrossSession()           ~20-50ms  ─┤
└─ safeGetSessionHistory()            ~5ms      ─┘
  ↓
safeAllocate() + 截断 + 格式化        ~6ms
```

**实现方式：** 使用 Java 22 Virtual Thread + `CompletableFuture` 并行执行四路检索：

`java
// ContextAssembler.assemble() 中的并行检索
var retrievalFuture = CompletableFuture.supplyAsync(
    () -> cachedRetrieve(state.traceId(), state.goal(), strategyConfig),
    virtualThreadExecutor);
var kbFuture = CompletableFuture.supplyAsync(
    () -> safeRetrieveKnowledgeBaseSnippets(state.sessionId(), state.goal(), 5),
    virtualThreadExecutor);
var crossSessionFuture = CompletableFuture.supplyAsync(
    () -> safeSearchCrossSession(episodicMemory, state.goal(), state.sessionId()),
    virtualThreadExecutor);
var historyFuture = CompletableFuture.supplyAsync(
    () -> safeGetSessionHistory(workingMemory, state.sessionId(), state.goal()),
    virtualThreadExecutor);

// 等待所有检索完成
CompletableFuture.allOf(retrievalFuture, kbFuture, crossSessionFuture, historyFuture).join();
`

**预期收益：** 上下文组装延迟从 110-360ms 降至 56-206ms（节省 ~50%）。

### 6.3 Prompt 结构优化（激活 Provider Prefix Caching）

详见 §5.2。核心思路是将 Prompt 重构为「稳定前缀 + 动态后缀」结构，使 Provider 侧的 KV Cache 命中率最大化。

**对 TTFT 的影响：**
- Ollama：KV Cache 命中时，前缀部分的 KV 计算被跳过，TTFT 降低 30-60%
- DeepSeek：磁盘缓存命中时，TTFT 从秒级降至百毫秒级（长上下文场景效果尤为显著）
- Qwen：类似 DeepSeek

### 6.4 流式优先 + 预加载

**当前流程：** 上下文组装完成后才开始 LLM 调用。

**优化思路：** 在上下文组装的同时，预先建立与 LLM Provider 的连接（TCP/TLS 握手），减少 LLM 调用的连接建立延迟。

**实现方式：**
- 在 `ProviderAdapter` 层维护连接池（HTTP/2 多路复用）
- Spring AI 的 `ChatClient` 底层使用 `RestClient` / `WebClient`，可配置连接池参数
- 对于 Ollama 本地 Provider，连接建立延迟可忽略（localhost）

**预期收益：** 对云端 Provider（DeepSeek / Qwen）节省 50-150ms 的连接建立时间。

### 6.5 综合 TTFT 优化效果预估

| 优化措施 | 节省延迟 | 实现复杂度 |
|---------|---------|-----------|
| 上下文组装并行化 | 50-150ms | 中（Virtual Thread + CompletableFuture） |
| Prompt 结构优化（Prefix Caching） | 100-500ms（云端）/ 30-100ms（本地） | 低（Prompt 重排序） |
| 连接池预热 | 50-150ms（云端） | 低（配置调整） |
| SemanticCache 命中 | 跳过 LLM 调用（节省全部 LLM 延迟） | 中（实现 SemanticCache） |

**综合效果：** 非缓存命中场景下，TTFT 从 330-2400ms 降至 ~150-1500ms（降低 40-55%）。缓存命中场景下，TTFT 降至 ~20-50ms。

---

## 7. LifePilot 适配方案

### 7.1 实施优先级

基于 ROI（收益/成本比）排序：

| 优先级 | 优化项 | 预期收益 | 实现成本 | 涉及模块 |
|--------|--------|---------|---------|---------|
| P0 | Prompt 结构优化（激活 Provider Prefix Caching） | Token 成本降低 40-60%，TTFT 降低 30-60% | 低（重排序 ContextAssembler 输出） | `com.lifepilot.agent.context` |
| P1 | SemanticCache 落地实现 | 重复查询 100% Token 节省，TTFT 降至 ~20ms | 中（已有架构设计，需编码） | `com.lifepilot.llm.cache` |
| P2 | 上下文组装并行化 | TTFT 降低 50-150ms | 中（Virtual Thread + CompletableFuture） | `com.lifepilot.agent.context` |
| P3 | 连接池预热 | TTFT 降低 50-150ms（云端） | 低（配置调整） | `com.lifepilot.llm.adapter` |
| P4 | Tool Result Cache | 避免重复工具执行 | 低 | `com.lifepilot.tool` |

### 7.2 P0: Prompt 结构优化

**改动范围：** `ContextAssembler.buildEnhancedUserPrompt()` + `buildSystemPrompt()`

**具体改动：**
1. 确保 System Prompt 中包含完整的工具定义（Function Calling Schema），而非在 User Prompt 中动态注入
2. 在 User Prompt 中，将用户画像（`safeGetUserProfile`）放在最前面（半稳定区），动态检索内容放在后面
3. 会话历史使用 `DialogCompressor` 压缩后放在用户画像之后、动态内容之前

**验证方式：**
- 对比优化前后的 Prompt 结构，确认前缀稳定区的 Token 数量
- 通过 DeepSeek API 的 `cache_hit_tokens` 响应字段验证缓存命中率
- 监控 `LlmUsageTracker` 中的 Token 消耗变化

### 7.3 P1: SemanticCache 落地

**改动范围：** 新增 `com.lifepilot.llm.cache.SemanticCache` 类 + 修改 `LlmRouter.call()`

**设计要点：**
- 完全遵循 `docs/architecture/llm-router.md` §7.6 的设计
- 基于 SQLite + sqlite-vec 实现向量存储（复用已有基础设施）
- 缓存键包含 scene + phase + normalized prompt
- 余弦相似度 >= 0.92 判定命中（`LlmConfigProperties.CacheConfig.similarityThreshold`）
- TTL 失效（默认 3600s）+ LRU 淘汰（默认 10000 条）
- `LlmRouter.call()` 在 Provider 故障转移循环之前插入缓存查询
- `LlmRouter.call()` 在成功响应后异步写入缓存

**流式响应处理：**
- `LlmRouter.stream()` 不走 SemanticCache（流式场景缓存收益低，实现复杂度高）
- 或者：缓存完整响应文本，命中时将文本拆分为 Token 模拟流式返回

### 7.4 P2: 上下文组装并行化

**改动范围：** `ContextAssembler.assemble()` 方法

**具体改动：**
- 将四路检索（HybridRetriever / 知识库 / 跨会话 / 会话历史）从串行改为并行
- 使用 `Executors.newVirtualThreadPerTaskExecutor()` 创建 Virtual Thread 执行器
- 使用 `CompletableFuture.allOf()` 等待所有检索完成
- 保持降级容错逻辑不变（单个检索失败不影响其他检索）

**注意事项：**
- SQLite 的 WAL 模式支持并发读取，四路检索并行不会产生锁冲突
- sqlite-vec 的向量搜索是 CPU 密集型，并行化在多核机器上收益更大
- 需要确保 `retrievalCache`（ConcurrentHashMap）在并行场景下的线程安全性（已满足）

### 7.5 与已有模块的集成点

| 集成点 | 已有接口 | 改动方式 |
|--------|---------|---------|
| `LlmRouter.call()` | 直接走 Provider 故障转移 | 前置插入 SemanticCache 查询 |
| `LlmRouter` 构造函数 | 仅注入 `ProviderRegistry` + `CircuitBreakerManager` | 新增 `@Nullable SemanticCache` 参数 |
| `LlmAutoConfiguration` | 已有 `SemanticCache` Bean 声明 | 实现 `SemanticCache` 类即可 |
| `ContextAssembler.assemble()` | 串行检索 | 改为并行检索 |
| `ContextAssembler.buildEnhancedUserPrompt()` | 当前 Prompt 结构 | 重排序为稳定前缀 + 动态后缀 |
| `LlmConfigProperties.CacheConfig` | 已定义配置 record | 无需改动 |

---

## 8. 设计决策与权衡

### 8.1 缓存存储选型：SQLite vs Redis

| 维度 | SQLite + sqlite-vec | Redis + RediSearch |
|------|--------------------|--------------------|
| 部署复杂度 | 零额外依赖（单 JAR 内嵌） | 需要独立 Redis Stack 实例 |
| 向量搜索 | sqlite-vec 余弦相似度，性能足够（万级缓存条目） | HNSW 索引，高性能（百万级） |
| 持久化 | 天然持久化（SQLite 文件） | 需要配置 RDB/AOF 持久化 |
| 并发性能 | WAL 模式支持并发读，单写 | 高并发读写 |
| 内存占用 | 低（磁盘为主，OS 页缓存） | 高（全内存） |
| 运维成本 | 零（嵌入式） | 需要监控、备份、版本升级 |

**决策：选择 SQLite + sqlite-vec。**

理由：LifePilot 是单用户个人 Agent，缓存条目规模预计在千级到万级，SQLite 的性能完全满足需求。单 JAR 部署是核心架构约束，引入 Redis 会显著增加部署复杂度，违背项目的「零运维」设计目标。sqlite-vec 已在记忆系统中验证过稳定性和性能。

### 8.2 缓存粒度：完整响应 vs 响应片段

| 方案 | 优点 | 缺点 |
|------|------|------|
| 缓存完整 LLM 响应 | 实现简单，命中即返回 | 长响应占用存储空间大 |
| 缓存响应片段/摘要 | 存储效率高 | 需要额外拼接逻辑，实现复杂 |

**决策：缓存完整响应。**

理由：LifePilot 的 LLM 响应通常在 200-2000 Token（约 0.5-5KB 文本），存储开销可忽略。完整响应缓存的实现最简单，命中时直接返回，无需额外处理。万级缓存条目的总存储量预计在 10-50MB，对 SQLite 毫无压力。

### 8.3 语义相似度阈值选择

| 阈值 | 命中率 | 误命中风险 | 适用场景 |
|------|--------|-----------|---------|
| >= 0.98 | 极低 | 极低 | 近乎精确匹配 |
| >= 0.95 | 低 | 低 | 保守策略 |
| >= 0.92 | 中 | 中低 | 平衡策略（当前默认） |
| >= 0.88 | 高 | 中 | 激进策略 |
| >= 0.85 | 很高 | 高 | 高风险，可能返回不相关响应 |

**决策：默认 0.92，可通过配置调整。**

理由：0.92 是 SCALM 论文推荐的平衡阈值，在 LifePilot 的个人 Agent 场景下，用户查询模式相对固定（如每天问 "今天有什么安排"），0.92 的阈值可以捕获这类语义等价的查询变体。通过 `lifepilot.llm.cache.similarity-threshold` 配置键允许用户根据实际体验调整。

### 8.4 并行检索 vs 串行检索

| 方案 | 延迟 | 复杂度 | 资源消耗 |
|------|------|--------|---------|
| 串行检索（当前） | sum(各检索延迟) = 110-360ms | 低 | 低（顺序执行） |
| 并行检索（优化） | max(各检索延迟) = 50-200ms | 中 | 中（4 个 Virtual Thread） |

**决策：采用并行检索。**

理由：四路检索之间无数据依赖，天然适合并行化。Java 22 Virtual Thread 的创建和调度开销极低（微秒级），不会引入显著的额外资源消耗。SQLite WAL 模式支持并发读取，不会产生锁冲突。预期节省 50-150ms 的上下文组装延迟，对用户体验改善明显。

### 8.5 流式响应的缓存策略

| 方案 | 优点 | 缺点 |
|------|------|------|
| 不缓存流式响应 | 实现简单，无额外复杂度 | 流式调用无法受益于缓存 |
| 缓存完整文本，命中时模拟流式 | 流式调用也能受益于缓存 | 需要 Token 分割 + 延迟模拟逻辑 |
| 仅对 `call()` 缓存，`stream()` 不缓存 | 清晰的职责分离 | 流式场景（Web UI）无缓存收益 |

**决策：Phase 1 仅对 `call()` 缓存，Phase 2 扩展到 `stream()`。**

理由：`LlmRouter.call()` 主要用于 Agent 内部推理（PLANNING / EXECUTING），这些场景对延迟敏感但不需要流式输出。`LlmRouter.stream()` 主要用于最终响应（RESPONDING），用户期望看到逐字输出的效果。Phase 1 先覆盖高频的 `call()` 场景，Phase 2 再实现流式缓存的模拟逻辑。

### 8.6 缓存失效策略

| 策略 | 适用场景 | 实现复杂度 |
|------|---------|-----------|
| TTL（时间过期） | 通用，防止过时数据 | 低 |
| LRU（最近最少使用） | 控制缓存大小 | 低 |
| 事件驱动失效 | 数据变更时主动失效 | 高 |
| 混合策略（TTL + LRU） | 兼顾时效性和空间 | 中 |

**决策：TTL + LRU 混合策略。**

理由：TTL 确保缓存数据不会无限期过时（默认 3600s），LRU 确保缓存大小不会无限增长（默认 10000 条）。事件驱动失效虽然更精确，但实现复杂度高，且 LifePilot 的个人场景下数据变更频率低，TTL + LRU 已足够。这与 `LlmConfigProperties.CacheConfig` 中已定义的 `ttlSeconds` 和 `maxEntries` 配置一致。


---

## 9. 调研参考汇总

### 9.1 学术论文与技术报告

| 来源 | 标题/主题 | 核心贡献 | 引用章节 |
|------|---------|---------|---------|
| Nizar et al. (2026) | Prompt Caching in Large Language Models: A Survey | Prompt Caching 综述，成本降低 45-80%，TTFT 降低 13-31% | §2.1 |
| SCALM (2025) | Semantic Caching for Automated LLM Management | 双阈值语义缓存，命中率提升 63% | §2.2 |
| KVFlow (2025) | Cross-Request KV Cache Streaming | 跨请求 KV Cache 流式传输 | §2.1 |
| LayerKV (2025) | Layer-wise KV Cache Quantization | 分层 KV Cache 量化，内存降低 40-60% | §2.1 |
| Strata (2025) | Hierarchical Context Caching | 层次化上下文缓存 | §2.1 |
| LLMLingua (Microsoft, 2024) | Token-level Prompt Compression | Prompt 压缩 20-40% | §2.4 |

### 9.2 开源项目

| 项目 | GitHub | 核心能力 | 引用章节 |
|------|--------|---------|---------|
| GPTCache (Zilliz) | github.com/zilliztech/GPTCache | 管道化语义缓存框架 | §3.1 |
| LMCache | github.com/LMCache/LMCache | 推理引擎 KV Cache 优化 | §3.2 |
| Redis LangCache | Redis Stack + RediSearch | 向量语义缓存 | §3.3 |
| Spring AI | github.com/spring-projects/spring-ai | Advisor 链 + ChatClient 缓存 | §3.4 |

### 9.3 Provider 官方文档

| Provider | 文档主题 | 核心信息 | 引用章节 |
|----------|---------|---------|---------|
| DeepSeek | Context Caching | 自动磁盘 KV Cache，0.1x 计费，64 Token 对齐 | §4.1 |
| Qwen (阿里通义) | Context Cache | 三种缓存模式（隐式/显式/会话），0.1x 计费 | §4.2 |
| Anthropic | Prompt Caching | 显式 cache_control 标记，0.1x 读取 / 1.25x 写入 | §4.3 |
| OpenAI | Automatic Caching | 自动前缀缓存，0.5x 计费，128 Token 对齐 | §4.4 |
| Ollama | KV Cache Management | 本地自动 KV Cache 复用 | §4.5 |

### 9.4 技术博客与行业分析

| 来源 | 主题 | 核心观点 | 引用章节 |
|------|------|---------|---------|
| fast.io (2025) | Agent Caching 五层模型 | L1-L5 缓存层级架构 | §2.3 |
| 业界综合分析 | Token 消耗冗余研究 | 典型应用 40-60% Token 冗余 | §2.5 |

---

> **文档状态**：调研完成，可作为 spec 规划的输入。
> **下一步**：基于本文档创建 `performance-optimization` spec（requirements → design → tasks）。

