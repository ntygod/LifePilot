# 知微（ZhiWei）全面审计报告与待办计划

> **审计日期**：2026-03-10
> **审计范围**：代码质量、产品功能、数据处理、前端覆盖
> **项目版本**：v0.2.0（Phase 1-4 完成，Phase 5 大部分完成，Phase 6 部分完成）

---

## 一、代码质量审计

### 1.1 死代码 / 空壳模块

| # | 问题 | 位置 | 优先级 | 建议 |
|---|------|------|--------|------|
| C-01 | `guardrail` 包仅含 `package-info.java`，实际护栏逻辑在 `observability/guardrail/` 和 `tool/` 中 | `com.lifepilot.guardrail` | 🟡 中 | 删除空壳包，或将其作为护栏策略定义的统一入口（当前架构文档已标注"包级标记"，但代码层面无实际内容，容易误导） |
| C-02 | `conversation` 模块仅 5 个文件（接口 + View record + 默认实现），功能极度精简 | `com.lifepilot.conversation` | 🟢 低 | 当前设计合理（对话存储在 `interaction/web/` 中处理），但需确认 `ConversationHistoryStore` 是否被所有入口（CLI / Web / Channel）统一使用，避免对话历史存储路径不一致 |

### 1.2 缺少测试覆盖的模块

| # | 模块 | 有测试包 | 优先级 | 建议 |
|---|------|---------|--------|------|
| C-03 | `a2a` | ❌ | 🟡 中 | A2A 协议涉及跨系统互操作，需要至少覆盖 Agent Card 序列化/反序列化、Client/Server 基本通信 |
| C-04 | `conversation` | ❌ | 🟡 中 | `ConversationViewService` 的查询逻辑需要单元测试 |
| C-05 | `datastore` | ❌ | 🔴 高 | 通用数据存储是用户数据的核心载体，7 个 Agent 工具 + Schema 校验 + FTS5 搜索 + 时序聚合都需要测试 |
| C-06 | `config` | ❌ | 🟢 低 | 全局配置类，Spring Boot 自动测试可覆盖 |

> 已有测试的 18 个模块：agent, eval, interaction, knowledge, llm, marketplace, mcp, media, memory, meta, multiagent, observability, prompt, sandbox, skill, sync, tool, workflow

### 1.3 跨模块集成风险点

| # | 问题 | 优先级 | 建议 |
|---|------|--------|------|
| C-07 | `AgentAutoConfiguration` 使用 `@Autowired(required = false)` 注入可选依赖（记忆系统、主动推理等），降级路径是否经过充分测试？ | 🟡 中 | 编写集成测试验证：(1) 全量 Bean 注入场景 (2) 最小化 Bean 注入场景（仅 LLM + Agent 核心） |
| C-08 | `ContextAssembler` 有完整版和基础版两种模式，切换逻辑是否可靠？ | 🟡 中 | 验证当记忆系统不可用时，ContextAssembler 是否正确降级到基础版 |
| C-09 | `GatewayAutoConfiguration` 管理 Channel 适配器生命周期，异常 Channel 是否会影响其他 Channel？ | 🟡 中 | 验证单个 Channel 适配器启动失败时，其他 Channel 和核心对话功能不受影响 |
| C-10 | `SkillAutoConfiguration` 注册 25+ Bean，Skill 加载失败是否有隔离？ | 🟡 中 | 验证单个 Skill 注册失败不会阻塞整个 Skill 系统初始化 |

### 1.4 潜在的代码质量问题

| # | 问题 | 优先级 | 建议 |
|---|------|--------|------|
| C-11 | 全量编译检查（`mvn compile`）— 需确认无编译警告 | 🔴 高 | 执行 `mvn compile -q` 并修复所有警告 |
| C-12 | Spring Context 完整加载测试 — `LifePilotApplicationTest` 是否能成功启动完整上下文 | 🔴 高 | 运行 `mvn test -pl . -Dtest=LifePilotApplicationTest` 确认 |
| C-13 | Flyway 迁移脚本经过多次迭代（V1~V45+），可能存在冗余或冲突 | 🟡 中 | 已在 todolist.md #4 记录，需要整理合并迁移脚本 |


---

## 二、产品功能审计

### 2.1 核心场景覆盖评估

| 场景 | 支撑模块 | 覆盖状态 | 备注 |
|------|---------|---------|------|
| 日常对话 | Agent + LLM + Memory | ✅ 完整 | 多轮对话 + 上下文记忆 + 流式响应 |
| 待办管理 | Builtin Skill (Todo) | ✅ 完整 | CRUD + 提醒 + 优先级 |
| 日程管理 | Builtin Skill (Schedule) | ✅ 完整 | 日程 CRUD + 冲突检测 |
| 习惯追踪 | Builtin Skill (Habit) | ✅ 完整 | 打卡 + 统计 + 连续天数 |
| 知识管理 | Knowledge + Memory L3 | ✅ 完整 | 文档上传 + 分块 + 混合检索 |
| 数据记录 | DataStore | ✅ 完整 | Schema-Free JSON + 全文搜索 + 时序聚合 |
| 代码执行 | Sandbox | ✅ 完整 | Process/Docker 双模式 |
| 工作流自动化 | Workflow | ✅ 完整 | YAML 定义 + 多种触发器 + 崩溃恢复 |
| 外部数据同步 | Sync | ✅ 完整 | CalDAV/Todoist/滴答清单/Obsidian |
| 主动提醒 | ProactiveReasoner | ✅ 完整 | 两阶段推理 + 智能降频 |
| 多模态理解 | Media + LLM | ✅ 完整 | 图片/文档/音频 |
| 外部工具扩展 | MCP | ✅ 完整 | MCP Client/Server + 工具桥接 |
| 多 Agent 协作 | MultiAgent + A2A | ✅ 完整 | HandoffTool + 预设专家 Agent |

### 2.2 功能缺口与增强建议

| # | 问题 | 优先级 | 建议 |
|---|------|--------|------|
| P-01 | 缺少「记忆可视化」能力 — 用户无法查看/管理自己的记忆数据（L2 情景记忆、L3 语义记忆、知识图谱） | 🟡 中 | 后端需新增 MemoryController（记忆查询/删除 API），前端需新增记忆浏览页面 |
| P-02 | 缺少「同步管理」入口 — 用户无法通过 Web UI 配置和管理外部数据源同步 | 🟡 中 | 后端需新增 SyncController（同步配置 CRUD + 手动触发 + 状态查看），前端需新增同步管理页面 |
| P-03 | 缺少「数据存储管理」入口 — DataStore 仅通过 Agent 工具访问，用户无法直接浏览/管理数据 | 🟢 低 | 后端需新增 DataStoreController，前端需新增数据浏览页面。但 DataStore 设计初衷是 Agent 工具，直接管理入口优先级较低 |
| P-04 | 缺少「沙箱管理」入口 — 用户无法查看沙箱状态、会话、执行历史 | 🟢 低 | 可在设置页面增加沙箱配置区域，或在轨迹回放中展示沙箱执行详情 |
| P-05 | 缺少「主动推理设置」入口 — 用户无法调整主动推理的频率、规则、开关 | 🟡 中 | 可在设置页面增加主动推理配置区域（启用/禁用、降频策略、信号类型开关） |
| P-06 | Eval 模块存在多个已知问题（详见 todolist.md #2） | 🔴 高 | 按 todolist.md 中 GPT 分析的优先级修复：真实轨迹接入 → evalRunId 语义修复 → 场景字段落地 → 评估核心去重 → 内置样例 |
| P-07 | 内置 Skill 场景覆盖 — 当前 5 个内置 Skill（Todo/Schedule/Habit/Memory/DataStore）是否足够？ | 🟢 低 | 个人助手核心场景已覆盖。更多场景（如财务记账、健康追踪、阅读笔记）可通过 YAML Skill 自扩展或 MCP 外部工具补充，无需内置 |
| P-08 | 预设 Agent 是否合适 — 当前预设写作/分析/调研三个专家 Agent | 🟢 低 | 三个预设 Agent 覆盖了最常见的委托场景，用户可通过 Web UI 自定义更多 Agent |


---

## 三、数据处理审计

### 3.1 数据存储架构评估

| 维度 | 现状 | 评估 |
|------|------|------|
| 结构化存储 | SQLite WAL 模式，Flyway 管理迁移 | ✅ 合理 |
| 向量存储 | sqlite-vec 独立数据库（`~/.zhiwei/vectors.db`） | ✅ 合理，分离避免主库膨胀 |
| 全文搜索 | FTS5 索引（记忆搜索 + 知识检索 + DataStore Note） | ✅ 合理 |
| 知识图谱 | SQL 表存储实体-关系，CTE 遍历 | ⚠️ 可优化（大规模图遍历性能待验证，已在 Phase 6 #23 规划 Agentic GraphRAG） |
| 数据目录 | `~/.zhiwei/`（主库 + 向量库 + 日志 + Skill YAML 等） | ✅ 合理 |

### 3.2 数据处理风险点

| # | 问题 | 优先级 | 建议 |
|---|------|--------|------|
| D-01 | 无数据备份机制 — 已在 KNOWN-LIMITATIONS.md 中记录，但用户可能不知道需要手动备份 | 🟡 中 | 考虑在设置页面增加「数据导出/备份」功能，或至少在首次启动时提示用户备份策略 |
| D-02 | Flyway 迁移脚本混乱（V1~V45+）— 多次迭代导致脚本冗余 | 🟡 中 | 已在 todolist.md #4 记录。建议在下一个大版本时合并为干净的基线迁移 |
| D-03 | 记忆数据生命周期 — MaRS 遗忘策略已实现，但用户无法手动管理记忆（查看/删除/修正） | 🟡 中 | 与 P-01 关联，需要记忆管理 API + UI |
| D-04 | 知识库文档删除后，向量索引和 FTS5 索引是否同步清理？ | 🟡 中 | 需要验证文档删除的级联清理逻辑，确保无孤立向量/索引数据 |
| D-05 | DataStore 集合删除后，关联的 FTS5 索引和属性定义是否同步清理？ | 🟡 中 | 需要验证级联删除逻辑 |
| D-06 | 对话历史无自动清理策略 — 长期使用后对话数据可能膨胀 | 🟢 低 | 考虑增加对话历史归档/清理策略（如保留最近 N 天，或按存储大小限制） |
| D-07 | SQLite 并发写入限制 — 单用户场景下问题不大，但工作流引擎 + 主动推理 + 同步引擎可能同时写入 | 🟢 低 | WAL 模式 + busy_timeout=5000ms 已缓解，但需要在高负载场景下验证 |

---

## 四、前端覆盖审计

### 4.1 现有页面清单（27 个视图）

| 功能区域 | 页面 | 对应后端能力 | 覆盖状态 |
|---------|------|------------|---------|
| 对话 | ChatView, ConversationsView, LandingView | Agent + LLM + Memory | ✅ 完整 |
| 知识库 | KnowledgeBaseView, KnowledgeBaseDetailView, KnowledgeBaseDocumentView | Knowledge | ✅ 完整 |
| Skill 管理 | SkillManageView, SkillDetailView | Skill | ✅ 完整 |
| 工具管理 | ToolsView, ToolDetailView | Tool + MCP | ✅ 完整 |
| MCP 管理 | McpServersView, McpServerDetailView | MCP | ✅ 完整 |
| 工作流 | WorkflowManageView, WorkflowDetailView | Workflow | ✅ 完整 |
| Agent 管理 | AgentsView, AgentDetailView | MultiAgent | ✅ 完整 |
| 插件市场 | MarketplaceView | Marketplace | ✅ 完整 |
| 轨迹回放 | TraceReplayView | Observability | ✅ 完整 |
| 数据分析 | AnalyticsUsageView, AnalyticsToolsView, AnalyticsAgentsView | Observability | ✅ 完整 |
| 设置 | SettingsView, SettingsModelsView, SettingsPreferencesView, SettingsShortcutsView | Config + LLM | ✅ 完整 |
| 依赖 | DependencyView | — | ✅ 完整 |
| 404 | NotFoundView | — | ✅ 完整 |

### 4.2 前端缺失的功能入口

| # | 缺失入口 | 对应后端能力 | 优先级 | 建议 |
|---|---------|------------|--------|------|
| F-01 | 记忆浏览/管理页面 | Memory（L2 情景记忆、L3 语义记忆、知识图谱可视化） | 🟡 中 | 新增 MemoryView — 展示记忆时间线、知识图谱关系图、支持搜索和手动删除 |
| F-02 | 同步管理页面 | Sync（CalDAV/Todoist/滴答清单/Obsidian） | 🟡 中 | 新增 SyncManageView — 连接器配置、同步状态、手动触发、冲突历史 |
| F-03 | 主动推理设置区域 | ProactiveReasoner | 🟡 中 | 在 SettingsPreferencesView 中增加主动推理配置区域（开关、降频策略、信号类型） |
| F-04 | 数据存储浏览页面 | DataStore | 🟢 低 | 新增 DataStoreView — 集合列表、文档浏览、搜索。优先级低因为 DataStore 主要面向 Agent 工具使用 |
| F-05 | 沙箱状态/历史页面 | Sandbox | 🟢 低 | 可在轨迹回放中展示沙箱执行详情，或在设置中增加沙箱配置 |
| F-06 | Eval 评估结果页面 | Eval | 🟢 低 | Eval 主要是开发者工具（JUnit 集成），Web UI 入口优先级低。但如果要做「AI 质量看板」，可以考虑 |

### 4.3 现有页面的体验问题

| # | 问题 | 优先级 | 建议 |
|---|------|--------|------|
| F-07 | UI 整体视觉效果和质感不足（已在 todolist.md #3 记录） | 🔴 高 | 需要 UI 重构优化，提升视觉质感和交互流畅感 |
| F-08 | 前端代码混乱（已在 todolist.md #3 记录） | 🔴 高 | 需要代码重构，统一组件风格、状态管理模式、API 调用方式 |


---

## 五、与现有 todolist.md 的对照

| todolist.md 项目 | 本审计对应项 | 状态 |
|-----------------|------------|------|
| #1 工作流模块优化 | — | ✅ 已完成 |
| #2 eval 模块优化 | P-06 | 待执行 |
| #3 UI 重构优化 | F-07, F-08 | 待执行 |
| #4 SQL 脚本整理 | C-13, D-02 | 待执行 |
| #5 所有功能人工测试通过 | 全局 | 待执行 |
| #6 文档、测试用例补全 | C-03~C-06 | 待执行 |

---

## 六、执行优先级排序

### 第一优先级（🔴 高 — 影响系统可靠性和用户体验）

| 序号 | 任务 | 来源 | 预估工作量 |
|------|------|------|-----------|
| 1 | 全量编译检查 + 修复警告 | C-11 | 0.5 天 |
| 2 | Spring Context 完整加载测试验证 | C-12 | 0.5 天 |
| 3 | DataStore 模块测试补全 | C-05 | 2 天 |
| 4 | Eval 模块修复（真实轨迹 + evalRunId + 场景字段） | P-06 | 3-5 天 |
| 5 | UI 重构优化（视觉质感 + 代码整理） | F-07, F-08 | 5-10 天 |

### 第二优先级（🟡 中 — 功能完整性和用户可管理性）

| 序号 | 任务 | 来源 | 预估工作量 |
|------|------|------|-----------|
| 6 | 新增记忆管理 API + 前端页面 | P-01, F-01, D-03 | 3-5 天 |
| 7 | 新增同步管理 API + 前端页面 | P-02, F-02 | 2-3 天 |
| 8 | 主动推理设置入口（设置页面扩展） | P-05, F-03 | 1-2 天 |
| 9 | A2A 模块测试补全 | C-03 | 1-2 天 |
| 10 | Conversation 模块测试补全 | C-04 | 1 天 |
| 11 | 跨模块集成测试（降级路径验证） | C-07~C-10 | 2-3 天 |
| 12 | 数据级联删除验证（知识库 + DataStore） | D-04, D-05 | 1 天 |
| 13 | Flyway 迁移脚本整理 | C-13, D-02 | 2 天 |
| 14 | 清理 guardrail 空壳包 | C-01 | 0.5 天 |
| 15 | 数据备份/导出功能 | D-01 | 2 天 |

### 第三优先级（🟢 低 — 锦上添花）

| 序号 | 任务 | 来源 | 预估工作量 |
|------|------|------|-----------|
| 16 | DataStore 浏览页面 | P-03, F-04 | 2 天 |
| 17 | 沙箱管理入口 | P-04, F-05 | 1 天 |
| 18 | Eval 评估结果页面 | F-06 | 2 天 |
| 19 | 对话历史自动清理策略 | D-06 | 1 天 |
| 20 | Config 模块测试 | C-06 | 0.5 天 |

---

## 七、审计结论

### 整体评价

知微项目的架构设计扎实，22 个模块分层清晰，依赖方向正确。核心引擎（Agent + LLM + Memory + Skill + Tool）的集成链路完整，可选依赖的降级机制设计合理。产品功能覆盖了个人 AI 助手的主要场景。

### 主要风险

1. **测试覆盖不均匀**：18/22 模块有测试，但 DataStore（用户数据核心载体）完全无测试，是最大风险点
2. **前端与后端能力不对称**：后端有 22 个模块，前端仅覆盖约 14 个模块的管理入口，记忆系统和同步系统对用户不可见
3. **Eval 模块数据基础不真实**：评估框架方向正确但未接入真实轨迹，作为质量门禁不可靠
4. **UI 质量**：视觉效果和代码质量需要提升，直接影响用户第一印象

### 建议执行路径

```
第一阶段（1-2 周）：编译验证 + 核心测试补全 + Eval 修复
    ↓
第二阶段（2-3 周）：记忆管理 + 同步管理 + 主动推理设置（补齐前端入口）
    ↓
第三阶段（2-4 周）：UI 重构优化（视觉 + 代码）
    ↓
第四阶段（1 周）：Flyway 整理 + 数据清理 + 收尾
```

总预估工作量：6-12 周（取决于 UI 重构深度）
