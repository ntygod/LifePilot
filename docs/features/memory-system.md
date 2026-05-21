# 记忆系统 — 特性说明

> **文档性质**：记忆模块面向使用者与集成方的一站式特性说明
> **模块归属**：`com.lifepilot.memory`
> **最后更新**：2026-05-10（合并 memory-advanced 以及本轮新能力的启用/调试/回退说明）
> **架构参考**：[memory-system.md](../architecture/memory-system.md) / [memory-data-flow.md](../architecture/memory-data-flow.md)

---

## 1. 功能概述

记忆系统为 Agent 提供四层记忆能力，但核心原则是：

- **当前对话连续性靠会话层**
- **L1 只做临时工作区**
- **跨会话对话靠 recall 工具**
- **长期事实和经验分别沉淀到 L3 / L4**

让"最近聊了什么"/"任务进行到哪一步"/"以前别的会话里提过什么"/"系统长期记住了哪些事实/偏好"四类信息各归其位。

---

## 2. 基础能力

### 2.1 当前会话上下文：热摘要单入口 + 最近完整轮次

`ContextAssembler` 通过 `ContextEngine` 读取当前 session 上下文，并通过一次 `HotMemoryDigestService.build(...)` 获取默认自动注入记忆：

- 最近完整轮次从会话层读取，按时间正序拼接；user / assistant 消息以完整 turn 保留
- `<user_profile_context>` / `<experience_context>` / `<memory_context>` 均只消费同一份 `HotMemoryDigest` 快照，不再并行运行旧画像 / 经验 / 相关记忆检索
- 当前 session 的连续性不再依赖 L1，也不依赖 L2 flush

### 2.2 L1：真正的临时工作区

L1 不保存聊天记录，只保存跨轮任务状态：

- `PendingDecisionItem`：等待用户确认
- `TaskStateItem`：任务执行进度（逐步记录每个工具调用的关键参数和结果摘要，超过 10 步折叠早期步骤为统计汇总）
- `WorkingSetItem`：下一轮还要继续使用的中间摘要

写入点：挂起/确认场景（`AgentPersistenceHandler`）、关键工具执行结果（`ToolExecutionCoordinator`，覆盖 `memory.create/update/tag`、`code`）、反思结论（`ReactAgentLoop`，截断至 300 字符）。

### 2.3 L2：跨会话 snippet recall

- `memory.recall` 不返回零散单条消息
- 先命中 `session_transcript_entries_fts`，再回到 `session_transcript_entries` 取命中前后完整轮次
- 默认排除当前 session，避免把当前对话检索回来

### 2.4 L3.5：热摘要单入口注入

自动注入到 Prompt 的长期信息统一经过 `HotMemoryDigestService` 收口：

- `USER_PROFILE`：L3 可消费画像实体或 `__consolidated_profile`；高置信 L4 偏好规则合并进来，但必须带可消费 L3 `source_entity_id`
- `EXPERIENCE`：非工具级、任务级高价值经验；工具级经验由 `ProviderMessageBuilder` + `ToolTipResolver` 按 toolId 动态前置到工具输出之前，不写入 `Observation.output`
- `PROJECT_MEMORY / FACTS`：项目约定和常用事实，合并进 `<memory_context>`

热摘要只读取 `MemoryQualityPolicy.isPromptConsumable(entity) == true` 的 L3 实体，记录 `source_entity_ids`，并在注入前脱敏。热摘要为空或构建失败时，本轮不回退旧路径；需要更多历史或事实时由 Agent 显式调用 `memory.search` / `memory.recall`。

### 2.5 记忆工具能力

| 工具 action | 作用 | 备注 |
|---|---|---|
| `memory(action=search)` | 搜索知识实体 | 返回质量、生命周期、scoreBreakdown |
| `memory(action=recall)` | 回忆别的会话的对话片段 | 默认排除当前 session |
| `knowledge.search` | 搜索资料文档 | 独立工具 |
| `memory(action=create)` / `update` / `delete` / `tag` | 实体 CRUD + 关系标记 | 经 `MemoryAccessPolicy` 校验 |
| `memory(action=cancel)` | 按语义批量归档 | 默认 `GOAL / EXPERIENCE / HABIT`，可通过 `entityTypes` 覆盖。与 `delete` 互补：`delete` 按 ID 精确删单条，`cancel` 按语义召回批量归档 |
| `memory(action=complete)` | 标记 `GOAL / PROJECT` 完成 | 驱动 lifecycle 状态机进入终态 |
| `memory(action=supersede)` | 旧实体被新实体替代 | 在 lifecycle 上建立 superseded_by 关系 |
| `memory(action=query-at-time)` | 时间点查询 | — |
| `memory(action=search-experience)` | 主动检索执行经验 | 排除工具级经验 |

归档链路（`delete` / `cancel` / 巩固 / 遗忘引擎）统一走 `SemanticMemory.archive()`：除主库生命周期变更外，还会登记 `memory_projection_outbox` DELETE 投影任务，确保归档实体不再被向量检索召回。

`HybridRetriever` 支持可选的 `RerankRouter` 精排和向量路径 pre-filter（当 `MemoryReadFilter` 限制 space/scope 时，先查合规实体 ID 集合做内存过滤，超过 1000 个时自动回退为后过滤）。

`EntityType` 枚举包含 12 种类型：`PERSON / ORGANIZATION / PLACE / EVENT / PROJECT / TOPIC / PREFERENCE / HABIT / GOAL / SKILL / EXPERIENCE / CUSTOM`。

### 2.6 L4 程序记忆

Agent 能够学习和复用用户的行为模式：

- `ProcedureTemplate`：操作模板，包含步骤序列、触发意图、成功率、执行次数；模板聚类默认启用
- `PreferenceRule`：偏好规则，按 category + key 存储，支持强化——用户反复确认的偏好权重更高
- `IntentMatcher`：向量相似度匹配用户意图与模板触发意图；集成到 `HybridRetriever` 冷检索链路，不作为默认自动注入入口；匹配失败静默处理

### 2.7 巩固管线

> 巩固管线已迁移到学习系统文档。详见 [agent-learning.md 特性说明](agent-learning.md)。

### 2.8 MaRS 认知遗忘

> 认知遗忘已迁移到学习系统文档。详见 [agent-learning.md 特性说明](agent-learning.md)。

---

## 3. 本轮新能力（feature/memory-evolution）

本轮落地 6 个记忆侧 spec + 2 个主动引擎 spec（主动引擎部分见独立文档）。全部默认开关关闭，零回归风险。

### 3.1 记忆老化与邻居回链（memory-staleness）

新事实写入时识别语义冲突的老邻居，迁入 `STALE_CANDIDATE` 生命周期态；召回时降权但仍可召回，Agent 命中时可自然追问确认。

**启用**（默认开启）：

```yaml
lifepilot:
  memory:
    staleness:
      enabled: true
      detection-similarity-threshold: 0.85      # 邻居识别最低语义相似度
      max-neighbors-per-detection: 3            # 单次最多标记
      detectable-types: [PREFERENCE, HABIT, LOCATION, GOAL]
      retrieval-penalty: 0.35                   # 召回惩罚比例
      neighbor-refresh-enabled: false           # 邻居刷新候选开关
```

**使用示例**：

- 用户说"我现在喜欢绿茶" → `RealtimeExtractor` 写入新 PREFERENCE
- `StalenessCoordinator` 异步检测语义相似的历史 PREFERENCE（如"用户喜欢咖啡"）
- 旧 PREFERENCE 被标记 `STALE_CANDIDATE`
- 下次召回时旧偏好得分被扣 0.35，新偏好排在前面
- 若用户日后再提"咖啡"相关，Agent 可追问确认

**调试**：开启 DEBUG 日志后：

```
staleness: entity=xxx marked=1 refreshCandidates=0
retrieval: STALE_CANDIDATE 降权 entity=yyy penalty=0.35
```

**依赖**：`EmbeddingRouter` 可用；`VectorSearcher.searchEntities` 不可用时 staleness 静默失效。

**回退**：`lifepilot.agent.learning.staleness.enabled: false`。关闭后旧有写入路径完全不变。

### 3.2 统一检索编排层（retrieval-orchestrator）

把 `HybridRetriever` / L3 EXPERIENCE 检索 / 知识库检索统一到 `RetrievalOrchestrator` 入口，返回统一的 `EvidenceBundle`。

**启用**：

```yaml
lifepilot:
  memory:
    retrieval-orchestrator:
      enabled: true
      default-top-k: 10
      per-source-top-k: 5
```

**调用**：

```java
@Autowired RetrievalOrchestrator orchestrator;

EvidenceBundle bundle = orchestrator.retrieve(
    "Rust 学习",
    RetrievalIntent.EXPERIENCE,
    10);

for (EvidenceItem item : bundle.items()) {
    System.out.println(item.name() + " score=" + item.score() + " source=" + item.sourcePath());
}
```

`QueryPlanner` 按 `RetrievalIntent` 选 source：

- `FACT` → hybrid + knowledge-base
- `EXPERIENCE` → experience + hybrid
- `GENERAL` → 三路全走

**回退**：`lifepilot.memory.retrieval.orchestrator.enabled: false`。关闭后所有 orchestrator Bean 不装配，`HybridRetriever` / 工具链不受影响。

### 3.3 记忆 REM 式联想巩固（memory-rem-consolidation）

巩固管线第 7 步：基于 L3 高 importance 实体做跨实体联想，LLM 识别潜在语义关系，合格候选落文件审计。

**启用**（默认关闭）：

```yaml
lifepilot:
  memory:
    rem:
      enabled: true
      seed-limit: 10
      neighbor-limit: 5
      seed-types: [GOAL, TOPIC, PROJECT]
      min-confidence: 0.65
      llm-timeout-seconds: 20
      deduplication-window-hours: 24
```

启用后每次巩固管线执行（cron 或手动 `ConsolidationPipeline.consolidate()`）会额外执行一次 REM 联想。

**输出位置**：`target/cache/memory-rem-associations/{yyyy-MM-dd}.json`（当天多次巩固合并到同一文件）。

**查询 API**：

```java
@Autowired AssociationCandidateStore store;

var today = store.load(LocalDate.now());
// today 是 List<AssociationCandidate>，可自行筛选/可视化/导出
```

**手动触发**：

```java
@Autowired ConsolidationPipeline pipeline;
pipeline.consolidate(true);  // manualTrigger=true
```

**调试提示**：

- 开启 DEBUG：`logging.level.com.lifepilot.agent.learning.consolidation.association=DEBUG`
- 看不到候选时确认：`enabled=true` / 有满足条件的 seed（类型属于 `seed-types`、description 非空、importance ≥ 其他实体）/ HybridRetriever 能返回邻居 / LLM 可用 / confidence 未被过滤

**成本提示**：默认 `10 seed × 5 neighbor = 50 次检索 + 10 次 LLM`；开启前确认成本可接受。

**回退**：`lifepilot.agent.learning.rem.enabled: false`，REM 相关 Bean 不装配，第 7 步直接跳过。

### 3.5 记忆安全加固（memory-security-polish）

对所有外部文本进入 L3 前做两层检测。本 spec 只落地可用组件，**不强制注入到写入链路**；接入节奏由未来 spec 按实际风险场景推进。

**启用**（默认关闭）：

```yaml
lifepilot:
  memory:
    security:
      injection-detection-enabled: true
      outlier-threshold: 3.0
      sample-window-size: 1000
      block-on-suspicious: false       # true 时 SUSPICIOUS 也当 BLOCKED
```

**调用**：

```java
@Autowired MemoryInjectionDetector detector;

var result = detector.detect(spaceId, candidate.description(), candidate.trustScore());
if (result.isBlocked()) {
    log.warn("记忆写入被阻断: {}", result.details());
    return;  // 拒绝写入
}
```

两层检测：

1. **Prompt injection 模式扫描**：`PromptInjectionPatternScanner` 中英 16 条正则库，命中 → `BLOCKED(PROMPT_INJECTION_PATTERN)`
2. **Bayesian trust 异常**：`SpaceTrustDistribution` 按 space 维护 trustScore 滑动窗口 + Mahalanobis 1D 距离检测，`> 3.0` → `SUSPICIOUS(TRUST_SCORE_OUTLIER)`

其他情况 → `PASS(CLEAN)` 并 `distribution.observe()` 记录样本。

**回退**：`lifepilot.memory.governance.security.injection-detection-enabled: false`。关闭后 `detector.detect()` 直接返回 PASS。

**M-P2-7 前端血缘展示**：后端 API 已就绪（`MemoryController.findRelations` / `findProvenance`），前端可直接对接实现血缘时间线与关系图。

### 3.6 Memory MCP Server（memory-mcp-server）

把知微记忆能力暴露为 MCP Server，供外部 Agent（Claude Desktop / Cursor / ChatGPT 桌面版）通过 MCP 协议读写。

**启用**（默认关闭）：

```yaml
lifepilot:
  memory:
    mcp-server:
      enabled: true
      server-name: "zhiwei-memory"
      server-version: "1.0.0"
```

启用后 `POST /api/mcp/memory` 接收 JSON-RPC 请求。

**接入 Claude Desktop**——`claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "zhiwei-memory": {
      "url": "http://localhost:8080/api/mcp/memory"
    }
  }
}
```

**3 个工具**：

- `memory_search`：向量 + FTS + 图融合搜索；输入 `{query, topK?}`，输出 `{items: [{entityId, entityType, name, description, score}]}`
- `memory_recall`：回忆历史对话；输入 `{query, limit?}`，输出 `{items: [{id, sessionId, goal, summary, messageCount, updatedAt}]}`
- `memory_create`：创建语义记忆实体；输入 `{name, entityType, description?}`

**手动测试**：

```bash
curl -X POST http://localhost:8080/api/mcp/memory \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

curl -X POST http://localhost:8080/api/mcp/memory \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

curl -X POST http://localhost:8080/api/mcp/memory \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"memory_search","arguments":{"query":"Rust","topK":5}}}'
```

**安全提示**：本 spec **未实现认证**，仅供本地回环使用。生产或公网暴露前需接入 token / mTLS，建议用反向代理（Nginx）限制访问来源。

**回退**：`lifepilot.memory.mcp-server.enabled: false`。关闭后 Controller / Handler / ToolRegistry 全部不装配。

---

## 4. 使用场景

### 4.1 普通连续对话

用户连续追问同一个话题时，Agent 直接依赖当前 session 最近完整轮次保持上下文，不需要额外回忆工具。

### 4.2 挂起后继续执行

某次执行需要用户确认高风险工具，系统把"等待确认"写入 L1 工作区。用户下一轮回来时，Agent 看到活跃工作区摘要，从上次中断点继续执行。

### 4.3 回忆别的会话

用户说"我之前提过旅游计划吗"，Agent 调用 `memory.recall` 检索别的 session 里的相关片段，而不是自动在主上下文中混入跨会话历史。

### 4.4 复用历史经验

任务与过去成功案例相似时，系统通过热摘要注入少量非工具级经验；工具级经验由 `ProviderMessageBuilder` 在构造 LLM 消息时通过 `ToolTipResolver` 按 toolId 动态前置到工具输出之前。`Observation.output` 保持纯 JSON，不被装饰污染，Skill 激活、Trace 回放、审计等下游解析都能拿到干净的工具原始输出。

### 4.5 行为模式学习

用户每天用知微管理待办事项，巩固管线识别出"每周一早上创建周计划"的重复模式，生成操作模板。下次周一早上用户说"帮我做周计划"时，`IntentMatcher` 匹配到该模板，Agent 按用户习惯的步骤执行，无需重新询问。同时，三个月前的一次性事件（如"参加某次会议"）因长期未访问被 LRU 标记遗忘候选——由于重要度 0.3 低于保护阈值，遗忘引擎将其归档。用户偏好"喜欢早起"因类型为 PREFERENCE 永远不被遗忘。

---

## 5. 配置项索引

### 5.1 通用

| 配置键 | 说明 |
|--------|------|
| `lifepilot.memory.enabled` | 记忆系统总开关 |
| `lifepilot.memory.workspace.enabled` | 是否启用临时工作区 |
| `lifepilot.memory.workspace.prompt-max-items` | Prompt 中最多注入多少条工作区摘要 |
| `lifepilot.memory.workspace.pending-decision-ttl-hours` | 待确认条目保留时长 |
| `lifepilot.memory.workspace.task-state-ttl-hours` | 任务状态保留时长 |
| `lifepilot.memory.workspace.working-set-ttl-hours` | 工作集保留时长 |
| `lifepilot.memory.workspace.cleanup-cron` | 工作区清理调度 |
| `lifepilot.memory.consumption.hot-digest.*` | L3.5 热摘要开关、分区预算和最大条目数 |
| `lifepilot.memory.retrieval.agentic-tool.*` | 记忆工具默认 TopK 等参数 |
| `lifepilot.memory.retrieval.*` | `HybridRetriever` 与记忆搜索工具参数 |

### 5.2 进阶子系统

| 配置键 | 默认 | 说明 |
|--------|------|------|
| `lifepilot.memory.store.procedural.template-enabled` | true | 操作模板聚类开关 |
| `lifepilot.memory.store.procedural.match-threshold` | 0.6 | 意图匹配相似度阈值 |
| `lifepilot.agent.learning.consolidation.cron` | `0 0 3 * * *` | 巩固管线 Cron |
| `lifepilot.agent.learning.consolidation.trigger-mode` | CRON | `CRON` / `IDLE` / `HYBRID` |
| `lifepilot.agent.learning.consolidation.lookback-days` | 7 | 回溯天数 |
| `lifepilot.agent.learning.forgetting.cron` | `0 0 4 * * SUN` | 遗忘引擎 Cron |
| `lifepilot.agent.learning.forgetting.max-forget-per-run` | 100 | 每次运行最大遗忘数 |
| `lifepilot.agent.learning.forgetting.recent-access-protection-days` | 7 | 近期访问保护天数 |
| `lifepilot.agent.learning.forgetting.high-access-count-protection` | 10 | 高频访问保护阈值 |
| `lifepilot.agent.learning.experience.*` | — | 经验注入、反馈、合并与隔离参数 |

### 5.3 本轮新能力

| 配置键 | 默认 | 说明 |
|--------|------|------|
| `lifepilot.agent.learning.staleness.enabled` | true | 记忆老化检测开关 |
| `lifepilot.memory.retrieval.staleness-retrieval-penalty` | 0.35 | `STALE_CANDIDATE` 召回惩罚比例 |
| `lifepilot.memory.retrieval.orchestrator.enabled` | false | 统一检索编排开关 |
| `lifepilot.agent.learning.rem.enabled` | true | REM 式联想巩固开关 |
| `lifepilot.agent.learning.rem.min-confidence` | 0.65 | 联想候选最低置信 |
| `lifepilot.memory.governance.security.injection-detection-enabled` | false | 记忆注入检测开关 |
| `lifepilot.memory.governance.security.block-on-suspicious` | false | SUSPICIOUS 是否按 BLOCKED 处理 |
| `lifepilot.memory.governance.mcp-server.enabled` | true | Memory MCP Server 开关 |
| `lifepilot.memory.eval.enabled` | false | Eval harness 总开关（profile 启用时覆盖） |

---

## 6. 当前限制

- `WorkingSetItem` 目前主写入点集中在工具执行和反思结论；更广泛的关键工具接入仍在演进
- 知识库、跨会话 recall 和冷 L3 检索仍依赖工具调用，而不是默认自动注入
- 热摘要当前按需构建快照，尚未落独立表或接入 outbox 失效队列
- REM 联想候选只落文件审计，尚未有"应用器"做人工/自动审阅后写入 L3 relations 主库
- `MemoryInjectionDetector` 已装配但默认未接入 `RealtimeExtractor` 等写入链路；风险场景接入节奏待后续 spec
- Eval harness LoCoMo / LongMemEval 数据集为英文，中文场景表现可能不同
- Memory MCP Server 未实现认证，仅建议本地回环使用
- 前端记忆血缘页（EntityDetailDrawer 血缘 Tab）后端 API 就绪，前端独立迭代

---

## 7. 未来方向

- 统一冷召回编排的 query 改写、并行 source 调用、学习型 reranker、`KnowledgeBaseSource` 真实实现、Agent 工具化 `memory.retrieve`
- 图记忆：relation 级质量字段补齐、对话关系自动提取候选、图投影 outbox 化
- REM 候选应用器：文件候选 → 审阅 → L3 relations 主库
- Staleness 精度提升：时间距离 + 显式否定词融合判定
- Idle-Driven 巩固：屏幕锁 / CLI 退出 / 长时间无对话触发
