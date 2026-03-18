# 知微主动智能升级 — 前沿调研与可行性分析

> **文档性质**：技术调研与升级规划
> **模块归属**：`com.lifepilot.agent.proactive`
> **最后更新**：2026-03-17

---

## 1. 现状诊断

### 1.1 当前架构

主动推理引擎（`ProactiveReasoner`）本质上是一个**规则驱动的定时通知系统**：

- **触发方式**：`@Scheduled` 固定间隔轮询（默认 30 分钟）
- **信号源**：仅内部数据（Todo/Schedule/Habit 三个内置 Skill + EpisodicMemory 时间信号）
- **评估逻辑**：CandidateProvider 使用确定性规则（截止时间差、日程临近度等）
- **LLM 角色**：仅用于 Stage 2 的内容润色和发送决策，不参与意图推理
- **频率控制**：三态状态机（NORMAL → REDUCED → MUTED），基于连续忽略计数
- **响应匹配**：关键词匹配判断用户是否回应了通知

### 1.2 核心缺失

| 能力 | 状态 | 说明 |
|------|------|------|
| 用户意图推理 | ❌ 缺失 | 不能从对话历史推断用户潜在需求 |
| 长期目标维护 | ❌ 缺失 | 无法将未满足的需求转化为持续监控任务 |
| 行为模式学习 | ❌ 缺失 | 不了解用户的活跃时段、偏好主题、响应模式 |
| 自适应推送时机 | ❌ 缺失 | 固定间隔轮询，不考虑用户当前状态 |
| 外部环境感知 | ⚠️ 不足 | 仅感知内部数据，不感知外部环境变化 |
| 语义响应匹配 | ⚠️ 不足 | 关键词匹配准确率有限 |

---

## 2. 前沿研究调研

### 2.1 ProAgent 架构族（2025.08）

来源：[emergentmind.com/topics/proagent](https://www.emergentmind.com/topics/proagent)

LLM 驱动的主动 Agent 架构家族，核心模块分解：

- **意图推理**：贝叶斯后验估计 P(θ|u,h)，从用户历史推断潜在需求
- **代价敏感规划**：π* = argmax E[R(s,a) - λC(a)]，平衡信息增益和行动代价
- **深度多模态执行**：递归子查询分解，跨多个应用并行交互
- **个性化记忆**：会话轨迹和摘要反馈到先验，加速未来推理

实验数据：用户操作量减少 30-50%，个性化贡献 15% 的任务完成时间改善。

### 2.2 Long-term Task-oriented Agent（2026.01，美团）

来源：[arXiv 2601.09382](https://arxiv.org/html/2601.09382v1)

与知微场景高度相关的研究，提出两个核心能力：

- **Intent-Conditioned Monitoring**：Agent 自主将用户未满足的请求转化为结构化监控任务，包含触发类型（TIME/EVENT）和触发条件描述
- **Event-Triggered Follow-up**：环境状态变化满足条件时主动唤醒用户，不满足时保持沉默（KEEP_SILENT）

关键设计：
- 任务状态追踪：PENDING → IN_PROGRESS → COMPLETED/FAILED
- 意图漂移处理：用户意图随时间演变，Agent 检测变化、更新触发器、执行多阶段主动跟进
- 结构化 JSON 响应：proactive_action + trigger_condition + task_description

实验数据：微调 Qwen3-32B 在复杂场景（含意图漂移）达到 85.19% 任务完成率，超过 Claude-sonnet-4 的 72.22%。

### 2.3 ContextAgent（NeurIPS 2025）

来源：[neurips.cc/virtual/2025/poster/115593](https://neurips.cc/virtual/2025/loc/san-diego/poster/115593)

- **分层上下文感知**：从多维感知数据（视频、音频、传感器）提取层次化上下文
- **On-demand Tiered Perception**：按需分层感知，不是所有信号都全量处理
- **Persona-aware Proactivity**：结合用户画像和历史数据预测主动服务的必要性

核心洞察：现有主动 Agent 要么依赖封闭环境观察做直接 LLM 推理，要么用规则通知——两者都不够。

### 2.4 ProPerSim / ProPerAssistant（ICLR 2026）

来源：[iclr.cc/virtual/2026/poster/10009484](https://iclr.cc/virtual/2026/poster/10009484)

- **主动性 + 个性化的统一**：通过用户-助手模拟框架，助手持续从用户反馈中学习
- **Retrieval-Augmented + Preference-Aligned**：检索增强 + 偏好对齐
- 32 种不同用户画像实验，助手能自适应策略并持续提升用户满意度

### 2.5 ProAgentBench（2026.02）

来源：[arXiv 2602.04482](https://arxiv.org/html/2602.04482v2)

定义了主动 Agent 的两个核心能力维度：

- **时机预测（When to intervene）**：判断何时介入最合适
- **辅助内容生成（How to assist）**：生成什么样的帮助内容

28000+ 真实用户事件数据集，关键发现：
- 长期记忆和历史上下文显著提升预测准确率
- 真实数据训练效果远超合成数据

### 2.6 Context-Aware Proactive Reasoner

来源：[emergentmind.com/topics/context-aware-proactive-reasoner](https://www.emergentmind.com/topics/context-aware-proactive-reasoner)

经典分层架构：
- **感知层**：传感器/中间件数据采集
- **上下文建模层**：OWL 本体 / 层次状态机 / 向量表示
- **推理引擎层**：概率推理 + 符号推理 + 预测（马尔可夫链）
- **行动/推荐层**：主动适配 API

### 2.7 OpenClaw 主动性架构（2026.02）

来源：[joumenharzli.com](https://www.joumenharzli.com/blog/proactive-ai-agents-the-architecture-behind-openclaw/) / [kryll.io](https://blog.kryll.io/openclaw-hooks-cron-heartbeat-ai-agent-automation/) / [playbooks.com](https://playbooks.com/skills/openclaw/skills/proactive-agent-3-1-0)

2026 年初最受关注的开源 Agent 框架，核心主动性设计围绕三个轻量级机制：

- **Heartbeat（心跳评估）**：每 N 分钟注入 HEARTBEAT.md 清单到 Agent 上下文，Agent 逐项用确定性规则检查条件是否满足，有事就行动，没事回复 HEARTBEAT_OK 静默丢弃。关键在于"判断力驱动"——不是每次都通知，而是每次都评估，大部分时候静默
- **Cron Jobs（定时任务）**：精确时间触发，分两种架构：
  - `systemEvent`：发到主会话，需要 Agent 注意力（适合交互式任务）
  - `isolated agentTurn`：生成独立子 Agent 自主执行，不占主会话（适合后台维护）
- **Event-Driven Webhooks（事件驱动）**：外部系统推送事件到 Gateway，Agent 接收后推理并通知用户，从 pull 变 push

proactive-agent skill（v3.1.0）的额外模式：
- **Reverse Prompting**：Agent 主动问用户"我能帮你做什么"，而非等用户想到
- **Pattern Recognition Loop**：追踪重复请求，3 次以上自动提议自动化
- **Outcome Tracking Loop**：记录重要决策，7 天后自动跟进结果
- **WAL Protocol**：写前日志，关键信息在响应前先持久化，防止上下文丢失

核心设计哲学：**确定性规则优先，LLM 仅在规则无法判断时介入**。这使得 token 消耗极低，大部分心跳周期零 LLM 调用。

---

## 3. 已有基础设施评估

### 3.1 可直接复用的模块

| 模块 | 关键能力 | 复用方式 |
|------|---------|----------|
| `LlmRouter` | `call()` / `callEntity()` / `stream()` / 场景路由 / 结构化输出 | 直接用于意图推理、语义匹配、价值评估 |
| `EpisodicMemory` | `getRecent()` / `search()` / `getByIntent()` / `countConversations()` | 行为模式统计的数据源 |
| `ScheduledTaskService` | cron/interval/once 触发 + 持久化 + 状态管理 | 复用做长期目标的条件化监控 |
| `Sync 模块` | CalDAV / 滴答清单 / Obsidian 连接器 | 外部信号源的数据通道 |
| `SignalSource` 接口 | 插件化信号采集 | 新增信号源只需实现接口 |
| `CandidateProvider` 接口 | 插件化候选评估 | 新增评估逻辑只需实现接口 |
| `NotificationService + SSE` | 通知分发通道 | 直接复用 |
| `FrequencyStateManager` | 频率控制 + 持久化 | 扩展为偏好学习的数据基础 |

### 3.2 约束条件

| 约束 | 影响 |
|------|------|
| 单机 SQLite | 不能做大规模行为数据分析或模型训练 |
| 无自训练能力 | 不能训练 reward model 或微调 LLM |
| 无传感器数据 | 不是穿戴设备/手机 App，无多模态感知 |
| LLM Token 成本 | 每次推理周期调 LLM 有成本，不能无限制复杂推理 |
| 个人助手定位 | 单用户场景，无协同过滤数据基础 |

---

## 4. 可行性判定

### 4.1 能做（基于现有基础设施可落地）

| 能力 | 实现路径 | 复用基础 | 预期收益 |
|------|---------|---------|----------|
| **LLM 意图推理** | LlmRouter.callEntity() 从对话历史提取结构化意图 | LlmRouter + EpisodicMemory | 从「被动响应」升级为「理解用户想要什么」 |
| **长期目标维护** | 新增 GoalRegistry，持久化用户长期目标，支持状态流转 | ScheduledTaskService + SQLite | 跨会话追踪用户未满足的需求 |
| **意图条件化监控** | 将未满足目标转化为带触发条件的监控任务 | ScheduledTaskService | 不再依赖固定间隔，按条件触发 |
| **行为模式统计** | 从 EpisodicMemory 统计活跃时段、高频话题、响应模式 | EpisodicMemory | 了解用户习惯，优化推送时机 |
| **自适应推送时机** | 基于行为统计替代固定间隔轮询 | 行为模式统计结果 | 在用户最可能响应的时段推送 |
| **外部信号源扩展** | 实现 SignalSource 接口，接入 Sync 模块的外部数据 | Sync 连接器 | 感知外部环境变化（日历变更、任务更新等） |
| **语义响应匹配** | LlmRouter 替代关键词匹配，判断用户消息是否回应通知 | LlmRouter | 大幅提升响应识别准确率 |
| **简单偏好学习** | 基于确认/忽略/拒绝统计，SQLite 存偏好权重 | FrequencyStateManager + SQLite | 逐步学习用户对不同类型通知的偏好 |

### 4.2 做不到（缺乏必要基础设施）

| 能力 | 缺失原因 | 替代方案 |
|------|---------|----------|
| **贝叶斯意图推断** | 需要概率图模型和大量标注数据 | 用 LLM 结构化输出近似替代 |
| **代价敏感策略优化** | 需要 RL 训练环境 | 用启发式规则 + 偏好权重近似 |
| **多模态感知** | 无传感器数据源 | 聚焦文本和结构化数据信号 |
| **协同过滤** | 单用户场景 | 聚焦个体行为模式 |
| **Reward Model 训练** | 无训练基础设施 | 用 LLM 评估 + 统计反馈替代 |

---

## 5. 升级计划

### 5.1 Phase 1：LLM 意图推理 + 长期目标维护（核心突破）

**目标**：让 Agent 能理解用户想要什么，并跨会话追踪未满足的需求。

**新增组件**：
- `IntentExtractor`：每次对话结束后，用 LLM 从对话历史提取结构化意图（intention + constraints + status）
- `GoalRegistry`：持久化用户长期目标，支持 PENDING → IN_PROGRESS → COMPLETED → ABANDONED 状态流转
- `IntentConditionedMonitor`：将 PENDING 目标转化为带触发条件的监控任务，复用 ScheduledTaskService

**改造点**：
- `ProactiveReasoner.executeReasoningCycle()` 增加 Stage 0：从 GoalRegistry 加载活跃目标
- `PolicyEngine.evaluate()` 增加目标驱动的候选生成（不仅是信号驱动）

**预期效果**：
- 用户说「帮我关注下周五之前机票价格」→ 提取为 Goal → 定期检查 → 价格变化时主动通知
- 用户说「提醒我下周开会前准备 PPT」→ 提取为 Goal → 会议前一天主动提醒

### 5.2 Phase 2：行为模式学习 + 自适应时机

**目标**：了解用户习惯，在最佳时机推送。

**新增组件**：
- `UserBehaviorAnalyzer`：从 EpisodicMemory 统计用户行为模式
  - 活跃时段分布（按小时统计对话频率）
  - 高频话题分类
  - 响应模式（平均响应延迟、忽略率按时段分布）
- `AdaptiveScheduler`：替代固定间隔的 @Scheduled，根据行为模式动态调整推理时机

**改造点**：
- `FrequencyStateManager` 扩展：从三态状态机升级为带权重的偏好模型
- `ProactiveConfigProperties` 新增行为分析相关配置

**预期效果**：
- 用户通常晚上 9-10 点活跃 → 推送集中在该时段
- 用户对健康类通知忽略率高 → 自动降低该类通知频率

### 5.3 Phase 3：外部信号源 + 语义响应匹配

**目标**：感知外部环境变化，提升响应识别准确率。

**新增组件**：
- `CalendarChangeSignalSource`：监听 CalDAV 同步的日历变更事件
- `ExternalDataSignalSource`：监听滴答清单/Obsidian 同步的数据变更
- `SemanticResponseMatcher`：用 LLM 替代关键词匹配，判断用户消息是否回应了某个通知

**改造点**：
- `ResponseTracker.isRelated()` 从关键词匹配升级为 LLM 语义匹配
- `SignalCollector` 自动发现并注册新的 SignalSource Bean

**预期效果**：
- 日历新增会议 → 自动提醒准备相关材料
- 用户回复「好的我知道了」→ 语义匹配识别为确认（而非关键词匹配失败）

### 5.4 Phase 4：偏好对齐 + 价值评估

**目标**：持续学习用户偏好，减少无效打扰。

**新增组件**：
- `PreferenceStore`：持久化用户对各类通知的偏好权重（基于确认/忽略/拒绝统计）
- `ProactiveValueEstimator`：在发送前用 LLM 评估通知的预期价值，结合偏好权重决定是否发送

**改造点**：
- `ProactiveReasoner` Stage 2 从「内容润色 + 发送决策」升级为「价值评估 + 偏好对齐 + 内容生成」
- `FrequencyStateManager` 集成 PreferenceStore 的权重数据

**预期效果**：
- 通知发送前经过价值评估，低价值通知被过滤
- 用户偏好随时间自动调整，无需手动配置

---

## 6. 优先级与依赖关系

```
Phase 1（意图推理 + 目标维护）
    ↓
Phase 2（行为模式 + 自适应时机）← 依赖 Phase 1 的 GoalRegistry 数据
    ↓
Phase 3（外部信号 + 语义匹配）← 可与 Phase 2 并行
    ↓
Phase 4（偏好对齐 + 价值评估）← 依赖 Phase 2 的行为数据 + Phase 3 的语义匹配
```

Phase 1 是核心突破点，解决「理解用户想要什么」的根本问题。
Phase 2 和 Phase 3 可以并行推进。
Phase 4 是锦上添花，依赖前三个 Phase 的数据积累。

---

## 7. 参考文献

1. ProAgent 架构族 — https://www.emergentmind.com/topics/proagent
2. Long-term Task-oriented Agent (美团, 2026.01) — https://arxiv.org/html/2601.09382v1
3. ContextAgent (NeurIPS 2025) — https://neurips.cc/virtual/2025/loc/san-diego/poster/115593
4. ProPerSim / ProPerAssistant (ICLR 2026) — https://iclr.cc/virtual/2026/poster/10009484
5. ProAgentBench (2026.02) — https://arxiv.org/html/2602.04482v2
6. Context-Aware Proactive Reasoner — https://www.emergentmind.com/topics/context-aware-proactive-reasoner
7. Proactive Agent: Shifting LLM Agents from Reactive to Active (ICLR 2025) — https://openreview.net/forum?id=sRIU6k2TcU
8. OpenClaw Proactive Architecture — https://www.joumenharzli.com/blog/proactive-ai-agents-the-architecture-behind-openclaw/
9. OpenClaw Hooks/Cron/Heartbeat — https://blog.kryll.io/openclaw-hooks-cron-heartbeat-ai-agent-automation/
10. OpenClaw proactive-agent skill v3.1.0 — https://playbooks.com/skills/openclaw/skills/proactive-agent-3-1-0