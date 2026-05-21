# 学习系统 — 特性说明

> **文档性质**：学习系统面向使用者与集成方的特性说明
> **模块归属**：`com.lifepilot.agent.learning`（规划中）+ 当前 `com.lifepilot.memory.{experience,consolidation,forgetting}`
> **最后更新**：2026-06-01（从 features/memory-system.md 拆出）
> **架构参考**：[agent-learning.md](../architecture/agent-learning.md)

---

## 1. 功能概述

学习系统让 Agent 从每次交互中变得更好，而不是每次从零开始。核心能力：

- **对话学习**：从用户对话中自动提取事实、偏好、目标
- **经验学习**：从执行轨迹中提炼可复用的结构化经验
- **巩固管线**：定期将碎片知识巩固为结构化模板和画像
- **认知遗忘**：衰减归档低价值记忆，保持知识库精简
- **效果追踪**：从反馈中动态调整经验权重

---

## 2. 对话学习（RealtimeExtractor）

对话结束后，系统自动从用户发言中提取长期有价值的信息：

- **提取内容**：事实（PERSON/PLACE/EVENT）、偏好（PREFERENCE）、习惯（HABIT）、目标（GOAL）
- **质量门控**：所有提取先落候选审计表，通过质量门控后才写入主库
- **证据追溯**：每条记忆必须带 `evidence_kind` 和 `evidence_excerpt`
- **项目隔离**：提取结果写入当前项目的 MemorySpace，不污染主账户

---

## 3. 经验学习

### 3.1 任务级经验（ExperienceSummarizer）

对话结束后，系统分析 Agent 的执行轨迹，提炼可迁移的结构化经验：

- 包含：任务目标、工具/步骤摘要、成功/失败结果、适用条件
- 去重：与已有经验向量相似度 ≥ 0.90 时合并，不创建新实体
- 质量门控：`TrajectoryQualityAssessor` 评估轨迹质量，低质量不提炼

### 3.2 工具级经验（SubtaskReflector）

从连续工具调用序列中提取细粒度经验：

- 触发条件：连续工具调用数 ≥ 3
- 带 `toolId` 标签，由 `ToolTipResolver` 在匹配工具时动态注入
- 不进入通用经验注入（HotMemoryDigest 过滤 tool-level 粒度）

### 3.3 效果追踪（EffectivenessTracker）

追踪注入经验的有效性并动态调整权重：

- 有效（任务成功 + 工具成功率高）→ importanceScore 提升
- 无效 → importanceScore 衰减
- 低于淘汰阈值 → 自动归档

---

## 4. 巩固管线

定期将碎片知识巩固为结构化知识，7 个阶段各自故障隔离：

1. **语义巩固**：高频提及的实体提升重要度
2. **程序巩固**：重复行为模式聚类为操作模板
3. **偏好同步**：L3 偏好实体同步为 L4 偏好规则
4. **经验合并**：相似经验去重合并为元经验
5. **用户画像巩固**：碎片画像 + 偏好 → 巩固画像
6. **经验提升**：高频高分经验提升为操作模板
7. **REM 联想**：跨实体联想，发现潜在语义关系

---

## 5. 认知遗忘（MaRS）

基于 MaRS 论文的六策略混合遗忘，防止知识库无限膨胀：

- FIFO / LRU / 优先级衰减 / 反思摘要 / 随机丢弃 / 混合策略
- 受保护实体永不遗忘：PREFERENCE / HABIT / GOAL 类型、高重要度、高频访问、近期访问
- 中等重要度实体先 LLM 压缩再归档，保留核心信息

---

## 6. 使用场景

### 6.1 行为模式学习

用户每天用知微管理待办事项，巩固管线识别出"每周一早上创建周计划"的重复模式，生成操作模板。下次周一早上用户说"帮我做周计划"时，`IntentMatcher` 匹配到该模板，Agent 按用户习惯的步骤执行。

### 6.2 经验复用

Agent 第一次帮用户抓取知乎内容时发现需要浏览器渲染，经验被提炼并存储。下次类似任务时，经验通过热摘要注入 Prompt，Agent 直接使用正确策略。

### 6.3 自动遗忘

三个月前的一次性事件（如"参加某次会议"）因长期未访问被 LRU 标记遗忘候选——由于重要度 0.3 低于保护阈值，遗忘引擎将其归档。用户偏好"喜欢早起"因类型为 PREFERENCE 永远不被遗忘。

---

## 7. 配置项索引

| 配置键 | 默认 | 说明 |
|--------|------|------|
| `lifepilot.agent.learning.experience.enabled` | true | 经验总结总开关 |
| `lifepilot.agent.learning.consolidation.cron` | `0 0 3 * * *` | 巩固定时 Cron |
| `lifepilot.agent.learning.consolidation.trigger-mode` | CRON | CRON / IDLE / HYBRID |
| `lifepilot.agent.learning.forgetting.cron` | `0 0 4 * * SUN` | 遗忘定时 Cron |
| `lifepilot.agent.learning.forgetting.max-forget-per-run` | 100 | 每次最大遗忘数 |
| `lifepilot.agent.learning.staleness.enabled` | true | 老化检测开关 |
| `lifepilot.agent.learning.rem.enabled` | true | REM 联想开关 |

完整配置列表见 [架构文档 §8](../architecture/agent-learning.md)。

---

## 8. 当前限制

- 巩固管线 7 阶段仍在同一 cron 中顺序执行，尚未解耦为独立调度
- 对比学习（ContrastiveLearner）产出的 insight 很少被检索命中，ROI 待验证
- 经验写入后需等待下次 cron 才能被 IntentMatcher 检索（学习闭环断点）
- REM 联想候选只落文件审计，尚未有应用器写入 L3 relations 主库

---

## 9. 未来方向

- 巩固调度解耦：7 阶段独立调度（EVENT / CRON / IDLE）
- 学习闭环打通：经验写入即时索引 + IntentMatcher 结果注入决策上下文
- 学习模块独立包：从 `com.lifepilot.memory.*` 迁移到 `com.lifepilot.agent.learning`
- 经验有效性多维评估：不仅看工具成功率，还看用户满意度
