# 记忆与主动能力演进 —— Gap 清单

> 本文件梳理截至 2026-05 知微**记忆系统 + 主动引擎**两个核心模块的已知 gap，面向后续迭代拆分。
> 前置阅读：`docs/architecture/memory-system.md` / `docs/architecture/memory-data-flow.md` /
> `docs/architecture/memory-advanced.md` / `docs/architecture/memory-domain-isolation.md` /
> `docs/architecture/proactive-reminder-engine.md`。
>
> - **P0** = 阻塞后续迭代（没有它，其他改造无法证明效果，或根因无法收敛）
> - **P1** = 用户可感知质量显著提升
> - **P2** = 长期健壮性 / 安全 / 可维护性
>
> 每项含：**问题描述 / 现状证据 / 建议方向 / 预估 / 依赖**。
> 方案一栏刻意保持粗粒度，后续每条 gap 再单独做详细分析、收集具体证据、拆为独立 spec。
>
> 产品定位前提：知微是**本地个人助手**，用户是普通人。
> - 评估与训练数据面向**开发期迭代**，不面向用户。
> - 用户能感知的只有"更少误打扰 / 更准记住 / 更懂何时该开口"。
> - 所有优化必须在单机、本地 LLM 可选、token 严格预算前提下成立。

---

## 0. 整体诊断（为什么写这份文档）

- **记忆模块**已覆盖 L0–L4 七层 + 质量字段 + 项目 overlay + projection outbox + 候选流水线 + MaRS
  遗忘 + 前端治理页面，工程深度属业界前列。现阶段的真实瓶颈是：
  - 没有离线回归通道，**每次改动无法客观比较"是不是变好了"**
  - 写入侧很强，**读消费侧的联想与证据链不够"活"**（新事实不会带动邻居更新；图扩展偏弱）
  - 对"时间让事实失真"的处理是被动的（依赖用户再次提及）
- **主动引擎**已有三级门控 + 8 个行为插件 + 信任阶梯 + LinUCB，但实际体验差的根因不在框架，而在：
  - 心跳唤醒不区分**任务边界 vs 任务中**，打扰时机错位是第一大体验杀手
  - 已落库的 candidate/delivery/feedback/policy 快照**从未回流成训练信号**
  - Gate 3 LLM 直接评分，**缺结构化推理**；缺**用户当前是否处于 focus 状态**的判据
- 本文档不给最终方案，只把 gap 讲清楚，**每条后续都需要独立 spec 前的调研**（§2.2 前沿调研规范）。

---

## 1. 记忆系统 Gap

### P0 — 阻塞迭代的根因

#### M-P0-1 离线回归基线缺失（开发期必修，非面向用户）
> **状态**：已拆为 feature 分支 `feature/memory-eval-harness`，**merged-ready**（16 个任务全部完成：配置/Loader/Judge/Probe/Report/Baseline/Runner/隔离环境/Replayer/AutoConfig/Maven profile/集成冒烟。53 个单元测试全通过，`mvn test -Pmemory-eval-quick` 可触发 fixture 冒烟）。
- **问题**：现在任何一个改动（加一个 listener、换一个质量阈值、调一个遗忘权重）都只能靠个案验证，无法回答"整体召回率 / 误召回率 / staleness / token 成本"是不是真的改善。
- **现状证据**：
  - `memory-system.md` §7 Phase H 已把"建立真实流式对话回归集"列为统一检索编排的前置
  - `memory-data-flow.md` §0.14 列出 5 个 source 的统一证据结构，但没有量化指标
  - 测试用例覆盖功能正确性（单元/集成），不覆盖召回质量漂移
- **方向**（粗粒度，待细化）：
  - 直接复用开源数据集做开发期回归：**LoCoMo**（多轮对话回忆）、**LongMemEval**（跨 session 长期记忆）、**BEAM**（1M/10M 规模）
  - 不构造面向知微用户的新数据集（用户是普通人，数据集对他们无价值）
  - 最小指标集：LLM-as-judge 正确率 / F1 / 每次召回 token 数 / p95 延迟 / 误召回率（stale 被当成 current 的比例）
  - 放在 `src/test/java/.../memory/eval/` 下作为可选重型测试（`@Tag("eval")`），Maven profile 控制是否跑
  - 报表落 `target/memory-eval-report/`，后续每次改动前后对比基线
- **产出的用处**：纯开发期工具，不进产品功能面，不暴露给用户
- **预估**：M（~1-2 周，数据集导入 + 指标实现 + 基线跑通）
- **依赖**：无；是 M-P1-*、M-P1-4 的**前置**

#### M-P0-2 Staleness / Confident-But-Wrong 检测机制缺失
> **状态**：已拆为 feature 分支 `feature/memory-staleness`，**merged-ready**（12 任务全部完成：LifecycleState.STALE_CANDIDATE 新增 + VectorBasedStaleConflictDetector + StalenessMarker + NeighborRefreshService + StalenessCoordinator + SemanticMemory afterCommit 集成 + HybridRetriever 降权 + ProactiveCacheInvalidator + AutoConfig 装配 + 架构/特性文档。28 单元测试通过）。关联的 M-P1-5 / C-P0-1 也在本 spec 同时落地。
- **问题**：`trust_score` 只对"新来证据的质量"打分。**一条 2 年前写入的高 trust_score 事实，不会因为时间长而被识别为可能过时**。用户说"我搬家了"，旧"住在北京"实体只在对话中 cancel 时才降权，否则继续被召回、继续进 HotDigest。
- **现状证据**：
  - `SemanticMemory.updateLifecycleState` / `ForgettingEngine` 都是基于 access/time 的通用衰减，没有"新事实暗示旧事实失效"的路径
  - `ConflictDetector` 只在同 space 的 upsert 时运行，不扫存量
  - 社区共识（Mem0 2026 博客、tianpan.co "When Unbounded Agent Memory Degrades Performance"）：staleness 是 2026 开放问题
- **方向**：
  - 新事实落盘时触发 `StalenessScanner`：对语义/图邻居做对比，发现 contradict/supersede 信号 → 老实体进中间态（候选名 `STALE_CANDIDATE`，介于 ACTIVE 和 ARCHIVED）
  - 召回时降权并在 `scoreBreakdown` 标注"可能过时"
  - Agent 在 HotDigest 命中时可自然追问"还是 X 吗？"，用户回复触发确认/否认路径
- **预估**：M（~1-1.5 周）
- **依赖**：无；可与 M-P0-1 并行

### P1 — 用户可感知质量提升

#### M-P1-3 L3 语义记忆缺 REM 式联想巩固
- **问题**：MaRS 六策略已覆盖"压缩/归档"这一半（类似 NREM 突触下调 + Hebbian 强化已通过 `EpisodicToSemanticConsolidator` 频次提升间接实现），但完全没做**跨实体联想生成**（REM 语义侧）。表现为 L3 图边偏稀疏，图扩展召回增益小。
- **现状证据**：
  - `memory-advanced.md` §3.4 描述的是"高频实体提升 importance"，不创造新关系
  - `memory-system.md` §2.8 图谱段承认"当前 SQL 图是读模型，关系主要来自显式写入和文档抽取"
  - `memory-advanced.md` §6.1 已留"Idle-Driven 记忆巩固"接口（`consolidate()` 手动入口）
- **方向**（借鉴 SCM / Letta Sleep-time Compute，但**本地轻量版**）：
  - 在现有 `ConversationCompletionHook` 之外新增空闲巩固：屏幕锁 / CLI 退出 / 长时间无对话 → 触发一次 `NightlyConsolidation`
  - 流程：近期高 importance seeds → 图 random walk → 候选新边（`related_to` / `causes`）→ 走候选流水线质量门控 → 合格者进 L3 关系主库
  - 不重算 embedding，不直改主库（保持 outbox / 候选语义）
  - 单用户场景算力足够，token 成本可控
- **预估**：M（~2 周）
- **依赖**：M-P0-1（有回归才能证明联想质量）

#### M-P1-4 统一检索编排（Phase H 落地）
- **问题**：`memory.recall` / `memory.search` / `memory.search-experience` / 知识库工具各自独立，Agent 要判断调哪个。缺 `memory-system.md` §7 Phase H 已规划的统一冷召回层。
- **现状证据**：`memory-system.md` §7 Phase H + `memory-data-flow.md` §0.14 已有蓝图，但尚未实现
- **方向**：按已有蓝图落地 `RetrievalOrchestrator + QueryPlanner + SourceAdapter + EvidenceBundle`，**不替换现有冷召回工具**，做为可选上层
- **预估**：L（~3 周）
- **依赖**：M-P0-1（没有回归集，"多路融合是否真的比单源好"无法证明）

#### M-P1-5 新事实写入不影响邻居（Agentic Memory Evolution 缺失）
- **问题**：`RealtimeExtractor` 把新候选写入主库后，相邻语义实体不会因此刷新自己的 description / importance。长期表现为"老的归老的，新的归新的"，跨时间的画像连贯性依赖 `UserProfileConsolidator` 周期跑批，时效弱。
- **现状证据**：
  - `memory-data-flow.md` §0.2.1 工具结果治理规则只覆盖写入来源，不覆盖写入涟漪
  - `memory-advanced.md` §3.6 `UserProfileConsolidator` 按源签名去抖，粒度较粗
- **方向**（借鉴 A-MEM NeurIPS 2025，arXiv:2502.12110）：
  - 新实体落库后找 top-3 语义邻居
  - 若新事实能补全/修正邻居的 description 或 importance，生成"邻居更新候选"也进 `memory_extraction_candidates`，走同一质量门控（不并发直改）
  - 可复用现有 outbox 语义
- **预估**：M（~1.5 周）
- **依赖**：M-P0-1、建议在 M-P0-2 之后（staleness + evolution 可协同）

### P2 — 长期健壮性与安全

#### M-P2-6 记忆注入防御（本地部署的剩余风险面）
- **问题**：即使本地部署，Channel 接入（飞书/钉钉/Telegram）转入的用户消息、工具结果中嵌入的文本、KB 文档、MCP 响应仍是注入面。MINJA 框架对生产 Agent 注入成功率 95%，标准 LLM 检测漏检 66%（参考文献：arXiv:2601.05504、arXiv:2603.02240）。
- **现状证据**：
  - `memory-data-flow.md` §0.7.1 已有 `evidence_kind / trust_level` 字段，但没有基于**分布统计**的异常识别
  - 没有对 KB 文档内 "ignore previous instructions" 类 payload 的扫描
- **方向**：
  - 维护 space 级 `trust_score` 分布；新候选计算 Mahalanobis 距离 + semantic outlier detection，异常值直接 `REJECTED(suspicious)` 入候选审计
  - 针对 KB 文档抽取链路，加一层 prompt injection 文本检测
  - 本地部署不需要云端分布式威胁情报，走**单机 Bayesian trust** 即可
- **预估**：M（~1.5 周）
- **依赖**：无；可与 M-P1-* 并行

#### M-P2-7 遗留：记忆治理页面的"为什么"血缘展示
- **问题**：`zhiwei-web/src/views/memory/` 的 EntityPanel / RelationPanel / TemplatePanel / PreferencePanel / ForgettingLogPanel 已经覆盖浏览、搜索、删除，但**没把"这条记忆为什么会在这"的血缘图完整展示**（evidence_excerpt / provenance chain / 衍生关系 / overlay 关系）。
- **现状证据**：
  - 后端 `MemoryController` 已支持 detail / related / relations 查询
  - 前端已有单条详情但血缘追溯不够完整
- **方向**：前端 detail 页补展 provenance 时间线 + overlay/derivation 关系图 + HotDigest 注入次数
- **预估**：S（~3-5 天）
- **依赖**：无；纯前端 polish

#### M-P2-8 记忆层暴露为 MCP Server（可选）
- **问题**：目前知微的记忆能力只有自己在用。2026 行业方向是把 memory 作为 MCP server 暴露给其他 Agent（Claude Desktop / Cursor / ChatGPT 桌面版）。
- **方向**：把现有 `memory.search/recall/create/update` 工具再包一层 MCP server，不改核心逻辑
- **预估**：S（~3 天）
- **依赖**：无；纯工程。**是否做看产品节奏**，非核心路径

---

## 2. 主动引擎 Gap

### P0 — 为什么现在必须默认关闭

#### P-P0-1 Heartbeat 不感知 workflow 边界（根因一）
- **问题**：`HeartbeatRunner` 纯按固定间隔唤醒 `ProactiveEngine`，不区分**用户正在任务中 vs 刚完成一段任务**。这是当前"打扰质量差"的第一根因。
- **现状证据**：
  - `HeartbeatRunner` 只看 `active-hours-start/end` 和间隔
  - `DecisionGate` 硬边界只覆盖静音时段 / 每日额度 / 偏好降级，**没有"是否处于任务边界"维度**
  - JetBrains 2026 田野研究（arXiv:2601.10253，15 开发者 / 5 天 / 229 干预）证据：post-commit 52% engagement，mid-task 62% dismissed——**同一内容差 21 个百分点**
  - CHI 2025 Goldilocks Time Window（arXiv:2504.09332）：时机错位比内容错位对体验破坏更大
- **方向**：
  - 新增 `BoundarySignalCollector`：订阅已有事件 `ConversationCompletedEvent` / `WorkflowExecutionCompleted` / `CronTaskCompleted`
  - 桌面端（Tauri）扩展：窗口 focus 切换、系统 DND、IDE commit（可选）
  - `DecisionGate` 把"是否在 boundary 窗口内（10 分钟）"作为阈值调整维度：boundary 内 NOTIFY 阈值 -0.15，boundary 外 +0.25
  - INTERRUPT 级保持不变（真紧急的就该打断）
- **预估**：S-M（~1 周）
- **依赖**：无；**ROI 最高的单点改造**

#### P-P0-2 训练数据闭环缺失（根因二）
- **问题**：`proactive_reminder_delivery` / `reminder_feedback` / `reminder_policy_snapshot` / `reminder_topic_profile` 四张表已在 V1 建好并持续落数据，但从未系统性回放成训练信号。当前策略调优只能靠改阈值。
- **现状证据**：
  - `proactive-reminder-engine.md` §7 已规划 `ReminderReplayService` + `ReminderPolicyTuner`，标记"已实现"
  - 但实际只做参数重估，没有把**高 reward 的 boundary + candidate + outcome 作为 Gate 3 few-shot 样例**回流
  - ProAgentBench 论文（arXiv:2602.04482）核心结论：真实数据 SFT 让准确率从 57.3% → 74.0%（+16.7%），远超 synthetic 数据
- **方向**：
  - `ProactiveOfflineReplayService` 周级跑批：从四张表构造 `(context, action, reward)` 三元组
  - 输出三种结果：
    1. LinUCB 参数重估（已有）
    2. Gate 2 阈值再校准（按主题类型差异化）
    3. **Gate 3 few-shot 样例库更新**（把 reward 最高/最低的 20 条塞进 prompt 示例）
  - 周报落盘，管理员可查看"本周 100 条主动行为回放"
- **预估**：M（~1-1.5 周）
- **依赖**：无；与 P-P0-1 并行

#### P-P0-3 Focus State 识别缺失（根因三）
- **问题**：`ImplicitSignalCollector` 只被动观察"用户是否 ignore/dismiss"，不能**预先识别"用户正处于专注/会议/全屏"**，只能事后降权。
- **现状证据**：
  - `ImplicitSignalCollector.java` 覆盖投递忽略 / 对话参与度 / 未命中
  - 没有窗口/焦点/DND 状态信号
- **方向**：
  - 桌面端（Tauri command）：读系统 focus assist / DND / 全屏状态 / 摄像头占用
  - Web 端：`document.visibilityState` + 连续键盘输入 5 分钟无停顿 = FOCUS_MODE
  - 对话端：用户本轮消息长度 + 速度 + 连续对话密度
  - FOCUS_MODE 下 `DeliveryEngine` 自动降级：NOTIFY → QUEUE；INTERRUPT 保留
- **预估**：M（~1 周，桌面 + Web + 对话三源）
- **依赖**：无

### P1 — 显著提升打扰质量

#### P-P1-4 缺 Goldilocks 时效窗口预测
- **问题**：现在决策粒度是"此刻要不要提"，没有"最晚多晚还有意义"。对 DueSoon / Commitment 类候选，错过窗口后继续提只会拉低信任。
- **现状证据**：
  - `ReminderScoringModel.calcTimingScore` 只做 boundary/habit 命中判断，不做窗口闭合
  - CHI 2025 Goldilocks 论文明确定义 window-end = deadline − p80 用户响应时长
- **方向**：
  - 每用户每 topic 类型维护 `response_latency_distribution`
  - 候选评估时计算 `window_end`，超过 `window_end × 0.8` 且 focus_mode 则跳过，记 `SKIPPED(window_closed)`
- **预估**：S-M（~1 周）
- **依赖**：P-P0-2（需要足够的反馈数据估 latency 分布）

#### P-P1-5 Gate 3 缺 Think-Before-Action
- **问题**：Gate 3 直接让 LLM 输出评分，没有结构化思考。小模型容易被表面信号误导。
- **现状证据**：
  - 当前 prompt 走 "判断+打分" 单步
  - ContextAgent（NeurIPS 2025，arXiv:2505.14668）证明 CoT 蒸馏 + think-before-action 可把 Acc-P 从 77% 升到 87%
  - 但 ProAgentBench 也发现：**小模型上 CoT 在简单场景反而掉 10%**（过度思考）
- **方向**：
  - Gate 3 Prompt 改为四段 `<think>观察 → 推断用户状态 → 评估必要性 → 选择动作</think>`
  - **只对 candidate score ≥ 0.6 启用**，低分快速决策；避免小模型"过度思考"
  - 长期可选：把高质量模型的推理 trace 蒸馏给本地 7B 模型
- **预估**：S（~3 天基础改造）；长期蒸馏另算
- **依赖**：P-P0-2（需要高质量回放样本作为 few-shot）

#### P-P1-6 行为插件缺少分层激活（记忆-行为对齐）
- **问题**：8 个行为插件并列执行 detect，每次心跳都要全部跑一遍 Gate 2，成本高且激活无结构。
- **现状证据**：`ProactiveEngine` 按顺序遍历所有 `ProactiveBehavior.detect()`
- **方向**：按依赖的记忆层分三组，每组共享激活门：
  - **事实驱动**（FollowUp / Insight）：触发条件 = L3 相关实体 staleness / 新证据
  - **经验驱动**（ContextPrep / TaskExecution）：触发条件 = HotDigest.EXPERIENCE 命中
  - **习惯驱动**（Reminder / Report）：触发条件 = L4 PreferenceRule 时间/频率
  - ClipboardBehavior / InfoSupplementBehavior 独立保留
- **预估**：M（~1 周）
- **依赖**：无；但与 M-P1-3/5 协同效果更好

### P2 — 智能化

#### P-P2-7 缺 Off-Policy Evaluation
- **问题**：LinUCB 只从"发送后反馈"学习，不评估"当时如果选了另一个动作会怎样"。
- **方向**：扩展 `ReminderReplayService` 为 OPE：
  - 对历史每个决策点，计算"若当时选 SOFT_PUSH 而不是 SKIP"的 importance-sampled reward
  - 输出 regret 曲线，自动触发阈值调整建议
- **预估**：M（~1.5 周）
- **依赖**：P-P0-2

#### P-P2-8 打断前的 Meta-Check
- **问题**：当前是"我觉得该打断"，不自问"用户知道他需要这个吗"。Re-grounding Proactivity（arXiv:2602.15259）证明这类自检能降低无效干预 30%+。
- **方向**：Gate 3 通过后插入 `self_confidence_check`：
  - 候选必须能回答三问：① 用户能清晰说出他需要这个吗？② 证据是对话原文还是推断？③ 若错了后果如何？
  - 三问得分 < 2.0 → 降级 QUEUE 而非 NOTIFY
- **预估**：S-M（~1 周）
- **依赖**：P-P1-5

---

## 3. 主动与记忆的协同 Gap（两侧都涉及，单独列出）

### C-P0-1 ProactiveMemoryBridge 的反向同步弱
- **问题**：主动引擎把行为推断回写 L3 PREFERENCE 已走通（`syncInsightToL3`），但**记忆侧变化不会主动通知主动引擎**。例如 L3 中用户刚显式否认了某偏好，主动引擎还在按旧偏好打分。
- **现状证据**：
  - `memory-data-flow.md` §0.8 说明 MemoryAccessPolicy 是唯一边界层
  - 但没有 `L3 → Proactive` 的事件订阅；`EntityLifecycleChanged` 有 7 个监听器，没有主动引擎
- **方向**：
  - 新增 `ProactiveCacheInvalidator` listener：订阅 `EntityLifecycleChanged` / `EntityWeightChanged`
  - 相关 topic / candidate 缓存失效，下次心跳重算
- **预估**：S（~3 天）

### C-P1-2 L4 PreferenceRule 的 Proactive 专属规则未分类
- **问题**：L4 `PreferenceRule.category` 目前是自由字符串。主动引擎的"主题偏好 / 频次偏好 / 时段偏好"和记忆模块的"饮食偏好 / 购物偏好"混在一起。
- **方向**：
  - 区分 `proactive_*` 前缀
  - 主动引擎只消费 `proactive_*` 和用户画像偏好；非 proactive 类偏好不参与打扰决策
- **预估**：S（~3 天）
- **依赖**：P-P1-6（分层激活会用到）

---

## 4. 推进建议（供后续拆分 spec 参考）

建议按以下顺序拆独立 spec（粒度参考 `spec-workflow.md`，每个 spec 1-2 周）：

| 顺序 | Spec 候选名 | 主要内容 | 建议优先级 |
|---:|---|---|---|
| 1 | `memory-eval-harness` | M-P0-1 开发期回归基线 | **必做先做** |
| 2 | `proactive-boundary-training` | P-P0-1 + P-P0-2 + P-P0-3 | 与 1 可并行启动 |
| 3 | `memory-staleness` | M-P0-2 + M-P1-5 + C-P0-1 | 依赖 1 |
| 4 | `proactive-timing-cot` | P-P1-4 + P-P1-5 + P-P1-6 + C-P1-2 | 依赖 2 |
| 5 | `memory-rem-consolidation` | M-P1-3 | 依赖 1、3 |
| 6 | `retrieval-orchestrator` | M-P1-4 | 依赖 1、3、5 |
| 7 | `memory-security-polish` | M-P2-6 + M-P2-7 | 可独立 |
| 8（可选） | `memory-mcp-server` | M-P2-8 | 看产品节奏 |

**关键判断**：`memory-eval-harness` 和 `proactive-boundary-training` 是两条路径的根。前者让记忆侧的每次迭代有客观比较，后者把主动引擎从"调阈值"模式切换到"数据驱动"模式。其他所有 gap 都建立在这两个之上。

---

## 5. 文档更新约定

本文件是**问题聚合视图**，**不是 spec**：

- 每条 gap 在拆 spec 前，须按 `spec-workflow.md` §2.2 执行前沿调研，补齐论文/开源项目证据、验证跨模块接口
- Spec 创建后，在本文件对应 gap 下追加 "→ 已拆为 spec `<name>`，状态 in_progress / merged"，但保留原条目不删除，便于追溯
- 发现新 gap 直接补入对应章节，并记录发现时间与出处
