# 性能优化 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.llm`（缓存层）+ `com.lifepilot.agent`（TTFT 优化）
> **最后更新**：2026-03

---

## 1. 功能概述

ZhiWei 性能优化模块通过两个方向提升用户体验：

1. **多层缓存机制**：减少冗余 Token 消耗，降低 LLM API 调用成本
2. **TTFT（Time To First Token）优化**：缩短用户发送消息到看到第一个字符的延迟

### 1.1 用户价值

- **响应更快**：首字响应时间从 330-2400ms 降至 150-1500ms（非缓存命中），缓存命中时降至 20-50ms
- **成本更低**：Token 消耗降低 40-60%（Provider Prefix Caching）+ 重复查询 100% 节省（SemanticCache）
- **体验更流畅**：上下文组装并行化消除检索等待感，流式输出更快启动

---

## 2. 核心特性

### 2.1 Provider Prefix Caching（P0 优先级）

通过优化 Prompt 结构，将 System Prompt + 工具定义构成稳定前缀，自动激活 Provider 侧的 KV Cache 复用能力。

**支持的 Provider：**
- Ollama：本地 KV Cache 自动复用
- DeepSeek：磁盘级 KV Cache，缓存命中 Token 按 0.1x 计费
- Qwen：内存/磁盘 KV Cache，缓存命中 Token 按 0.1x 计费

**用户无感知**：此优化完全在后端完成，用户无需任何配置或操作。

### 2.2 SemanticCache 语义缓存（P1 优先级）

基于语义相似度的 LLM 响应缓存。当用户发送与之前语义相似的查询时（如 "今天有什么安排" vs "今天的日程是什么"），直接返回缓存的响应，跳过 LLM 调用。

**核心能力：**
- 基于 sqlite-vec 的向量相似度匹配（余弦相似度 >= 0.92）
- scene 维度隔离（不同场景的缓存互不干扰）
- AgentPhase 感知（PLANNING / EXECUTING / RESPONDING 阶段独立缓存）
- TTL 时间过期（默认 3600s）+ LRU 容量淘汰（默认 10000 条）

### 2.3 上下文组装并行化（P2 优先级）

将 ContextAssembler 的四路检索（向量检索、知识库检索、跨会话检索、会话历史）从串行改为并行执行，利用 Java 22 Virtual Thread 实现。

**效果：** 上下文组装延迟从 110-360ms 降至 56-206ms。

### 2.4 连接池预热（P3 优先级）

为云端 Provider（DeepSeek / Qwen）维护 HTTP 连接池，避免每次 LLM 调用时的 TCP/TLS 握手延迟。

**效果：** 云端 Provider 调用延迟降低 50-150ms。

---

## 3. 使用场景

### 3.1 日常对话场景

用户每天早上问 "今天有什么安排"，SemanticCache 在第二次及后续查询时直接返回缓存响应（如果日程未变化），响应时间从秒级降至毫秒级。

### 3.2 多步任务场景

Agent 执行多步任务时（如 "帮我规划下周的学习计划"），每一步的 LLM 调用都受益于 Provider Prefix Caching（System Prompt + 工具定义前缀复用），Token 成本降低 40-60%。

### 3.3 知识库问答场景

用户针对知识库内容提问时，上下文组装并行化使得向量检索、知识库检索、跨会话检索同时进行，TTFT 显著降低。

---

## 4. 配置项

所有配置项均在 `application.yml` 中声明，使用 `lifepilot.llm.cache` 前缀：

| 配置键 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `lifepilot.llm.cache.enabled` | boolean | true | 是否启用 SemanticCache |
| `lifepilot.llm.cache.similarity-threshold` | double | 0.92 | 语义相似度命中阈值（0.0-1.0） |
| `lifepilot.llm.cache.ttl-seconds` | int | 3600 | 缓存条目过期时间（秒） |
| `lifepilot.llm.cache.max-entries` | int | 10000 | 缓存最大条目数（LRU 淘汰） |

**调优建议：**
- 如果发现缓存返回不相关的响应，提高 `similarity-threshold`（如 0.95）
- 如果缓存命中率过低，降低 `similarity-threshold`（如 0.88），但注意误命中风险
- 对于频繁变化的数据场景（如实时日程），降低 `ttl-seconds`（如 300）

---

## 5. 限制与约束

### 5.1 当前限制

- SemanticCache 仅对 `LlmRouter.call()`（非流式）生效，`stream()`（流式）暂不缓存
- 缓存命中依赖 Embedding 模型的语义区分能力，极端情况下可能误命中或漏命中
- Provider Prefix Caching 的效果依赖 Provider 侧的实现，不同 Provider 的缓存粒度和有效期不同
- 上下文组装并行化在单核机器上收益有限

### 5.2 未来扩展方向

- 流式响应缓存：缓存完整响应文本，命中时模拟流式返回
- Tool Result Cache：对幂等工具（查询类）的结果进行短期缓存
- 缓存预热：对高频查询在空闲时预热缓存
- 缓存统计仪表盘：命中率、节省 Token 数、平均响应时间等指标可视化
- 自适应阈值：根据历史命中率和用户反馈自动调整相似度阈值

---

> **文档状态**：完成，可作为 spec 规划的输入。
