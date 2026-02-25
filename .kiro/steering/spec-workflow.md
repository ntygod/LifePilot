---
inclusion: always
---

# LifePilot Spec 规划与任务执行规范

本文档定义 Kiro 在规划 spec 和执行开发任务时必须遵循的工作流规范。

---

## 1. 实施顺序

### 1.1 模块依赖图

> 最后更新：2026-02-25
> 已完成模块标注 ✅，进行中标注 🔧

```
Phase 0 — 项目骨架 ✅
  └─ Maven 项目结构 + Spring Boot 启动类 + 基础配置

Phase 1 — 核心引擎（自底向上）✅
  ├─ 1. LLM Router ✅（无依赖，其他模块的基础设施）
  ├─ 2. Agent 引擎核心 ✅（依赖 LLM Router）
  │     ├─ AgentState / AgentPhase / AgentAction
  │     ├─ StateReducer
  │     ├─ Budget
  │     ├─ TraceRecorder
  │     ├─ ContextAssembler（基础版，不含记忆检索）
  │     └─ AgentLoop
  ├─ 3. 工具系统 ✅（依赖 Agent 引擎）
  │     ├─ ToolContract / DynamicToolRegistry
  │     └─ GuardrailEngine
  └─ 4. MCP 协议支持 ✅（依赖工具系统）

Phase 2 — 记忆与知识 ✅
  ├─ 5. 记忆系统基础 ✅（依赖 LLM Router）
  │     ├─ L1 工作记忆 + L2 情景记忆
  │     ├─ SqliteVecStore + VectorSearcher
  │     └─ Flyway V5~V6
  ├─ 6. 语义记忆 + 知识图谱 ✅（依赖记忆系统基础）
  │     ├─ L3 语义记忆 + 时序知识图谱
  │     ├─ HybridRetriever（向量 + FTS5 + 图遍历）
  │     ├─ ConflictDetector（三级冲突检测）
  │     └─ Flyway V7
  ├─ 7. ContextAssembler 完整版 ✅（依赖记忆系统基础 + 语义记忆）
  │     ├─ 记忆检索槽位填充
  │     ├─ 对话压缩（DialogCompressor）
  │     └─ Token 预算动态分配
  └─ 8. 文档/知识库管理 ✅（依赖语义记忆）
        ├─ DocumentIngester + 多格式解析（PDF/Word/Markdown/TXT）
        ├─ 分块策略（FixedSize / Semantic / Heading）
        ├─ 多知识库实例管理
        └─ 可选 Reranker 精排

Phase 3 — 交互与技能
  ├─ 9. 内置技能插件 ✅（依赖工具系统 + 记忆系统）
  │     └─ Todo / Schedule / Habit / Memory 四个核心 Skill
  ├─ 10. Skill 系统 ✅（依赖工具系统 + MCP）
  │     ├─ SkillRegistry + SkillActivator
  │     ├─ YAML 声明式 Skill + 热加载
  │     ├─ SubAgent 激活模式
  │     └─ Skill 自扩展（Gap 检测 + YAML 生成 + 三重验证）
  ├─ 11. CLI 交互层 ✅（依赖 Agent 引擎 + Skill 系统 + MCP + LLM）
  │     ├─ JLine 3 交互式对话
  │     ├─ 快捷命令（todo/schedule/habit/llm/mcp/skill）
  │     └─ CLI 快速路径（简单命令跳过完整 Spring 初始化）
  ├─ 12. 主动推理引擎 ✅（依赖 Agent 引擎 + 记忆系统 + Skill 系统）
  │     ├─ ProactiveReasoner 两阶段推理
  │     ├─ FrequencyStateMachine 智能降频
  │     └─ SignalCollector 信号采集
  └─ 13. Gateway + Channel 适配器（依赖 Agent 引擎）
        ├─ MessageGateway 统一消息入口
        ├─ 6 层中间件管道（Auth → RateLimit → Security → Router → Execution → Audit）
        ├─ 三层工具安全策略（Global → Agent → Tool）
        └─ Channel 适配器（企微 / 钉钉 / 飞书 / Telegram）

Phase 4 — 高级能力
  ├─ 14. 多模态能力（依赖 LLM Router）
  │     ├─ LlmRouter 多模态路由扩展
  │     ├─ MediaProcessor 图片预处理
  │     ├─ ProviderCapability 能力声明（Chat / Embedding / Vision / Rerank / TTS / STT）
  │     └─ Apache Tika 文档格式检测
  ├─ 15. 工作流/自动化编排（依赖 Agent 引擎 + Skill 系统 + 主动推理）
  │     ├─ WorkflowEngine 执行引擎
  │     ├─ YAML 声明式工作流定义
  │     ├─ 触发器（Cron / Event / Condition / Signal）
  │     └─ 工作流状态持久化 + 崩溃恢复
  ├─ 16. 代码执行沙箱（依赖工具系统）
  │     ├─ SandboxBooter 抽象（Process / Docker / Remote）
  │     ├─ 会话级沙箱实例复用
  │     ├─ CodeValidator 危险操作预检
  │     └─ 护栏集成（CRITICAL 风险级别）
  └─ 17. 外部数据源同步（依赖 Skill 系统 + 记忆系统）
        ├─ SyncEngine + SyncConnector 抽象
        ├─ CalDAV / Todoist / 滴答清单 / Obsidian 连接器
        ├─ 冲突解决策略（Last-Write-Wins / 用户确认）
        └─ OAuth Token 安全存储

Phase 5 — Web UI + 可观测性
  ├─ 18. Web UI 框架搭建（Vue 3 + Vite + Pinia）
  │     ├─ frontend-maven-plugin 集成
  │     ├─ 对话页 + 设置页
  │     └─ SSE 流式响应
  ├─ 19. Web UI 功能页面（依赖知识库 + Skill 系统 + Gateway）
  │     ├─ 知识库管理页
  │     ├─ Skill / MCP 管理页
  │     ├─ 轨迹回放页
  │     └─ 工作流管理页
  └─ 20. 可观测性完善（依赖 Agent 引擎 + 工具系统）
        ├─ TraceQuery 轨迹查询 API
        ├─ GuardrailAdvisor Spring AI Advisor 横切注入
        ├─ DataRedactor 自动脱敏
        └─ 轨迹评估（工具选择正确性 + 步骤效率）

Phase 6 — 生态与进阶（远期）
  ├─ 21. 多 Agent 协作（依赖 Agent 引擎 + Skill 系统）
  │     ├─ AgentRegistry + AgentDefinition
  │     ├─ HandoffTool 委托工具模式（借鉴 AstrBot/OpenClaw）
  │     ├─ SubAgent 独立预算 + 独立上下文 + 差异化模型
  │     └─ 预设专家 Agent（写作 / 分析 / 调研）
  ├─ 22. A2A 协议支持（依赖多 Agent 协作）
  │     ├─ A2A Client/Server 实现
  │     ├─ Agent Card 能力声明
  │     └─ 跨系统 Agent 互操作
  ├─ 23. 记忆系统进阶（依赖语义记忆）
  │     ├─ L4 程序记忆（Procedural Memory）
  │     ├─ 记忆巩固管线（情景→语义 / 情景→程序）
  │     └─ MaRS 认知遗忘策略（FIFO / LRU / Priority Decay / Reflection-Summary / Hybrid）
  ├─ 24. 部署体验优化
  │     ├─ Docker 镜像 + docker-compose.yml
  │     ├─ GraalVM native image 探索
  │     ├─ start.bat / start.sh 一键启动脚本
  │     └─ 配置版本迁移机制
  └─ 25. 插件市场 / 社区生态（依赖 MCP + Skill 系统）
        ├─ Skill 发布 / 发现 / 安装机制
        ├─ GitHub 仓库索引
        └─ 安全审核 + 版本管理
```

### 1.2 执行原则

- 严格按依赖顺序执行，前置模块未完成不开始后续模块
- 每个 spec 完成后合并到 develop，再开始下一个 spec
- 同一 Phase 内无依赖关系的模块可以按序执行，但不并行规划
- 每个 spec 的 scope 控制在 1-2 周可完成的范围内，过大的模块拆分为多个 spec

---

## 2. Spec 规划流程

### 2.1 规划前检查

开始规划新 spec 前，必须确认：

1. 前置依赖模块已完成并合并到 develop
2. 当前在 develop 分支上，工作区干净（无未提交变更）
3. 已阅读对应的架构文档和特性文档
4. **接口假设验证**：对 design 文档中引用的所有外部模块接口，基于实际源码核对签名、字段和 Bean 注册（详见 #[[file:.kiro/steering/integration-checklist.md]] §1）

### 2.2 Spec 创建流程

1. 使用 Kiro spec 工作流创建 spec（requirements → design → tasks）
2. spec 的 feature_name 与模块名一致（如 `llm-router`、`agent-core`）
3. requirements 文档引用对应的架构文档和特性文档作为输入
4. design 文档聚焦于实现方案，不重复架构文档已有的内容
5. design 文档必须包含「依赖接口验证」表格，列出所有引用的外部接口及其源码验证状态（详见 #[[file:.kiro/steering/integration-checklist.md]] §1.4）
6. design 文档涉及跨模块接口变更时，必须包含「跨模块接口变更」表格（详见 #[[file:.kiro/steering/integration-checklist.md]] §4.2）
7. tasks 拆分到可独立提交的粒度

### 2.3 Spec 文档引用规范

在 spec 的 requirements 和 design 文档中，使用以下方式引用已有文档：

```markdown
参考文档：
- 架构设计：#[[file:docs/architecture/llm-router.md]]
- 特性设计：#[[file:docs/features/llm-router.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
```

---

## 3. 任务执行流程

### 3.1 开始执行前

1. 从 develop 创建 feature 分支：`git checkout -b feature/{spec-name}`
2. 确认 tasks.md 中的任务列表和依赖关系
3. 按任务编号顺序执行

### 3.2 执行单个任务

1. 更新任务状态为 in_progress
2. 编写实现代码
3. 编写对应的测试代码（单元测试 + 集成测试）
4. 运行测试确认通过
5. 检查代码诊断（getDiagnostics）确认无编译错误
6. git add + git commit（遵循提交规范）
7. 更新任务状态为 completed

### 3.3 执行子任务

- 先完成所有子任务，再标记父任务为 completed
- 每个子任务完成后独立提交
- 子任务之间如有依赖，按依赖顺序执行

### 3.4 任务完成后

1. 确认所有任务已完成
2. 运行完整测试套件确认无回归
3. 切回 develop 分支，合并 feature 分支：
   ```bash
   git checkout develop
   git merge --no-ff feature/{spec-name}
   ```
4. 删除 feature 分支：`git branch -d feature/{spec-name}`
5. 提交合并：`feat({scope}): 完成 {spec-name} 特性开发`

---

## 4. 代码质量检查点

### 4.1 每个任务完成时

- [ ] 代码编译通过（getDiagnostics 无错误）
- [ ] 单元测试通过
- [ ] 集成测试通过（如适用）
- [ ] 遵循编码规范（中文注释、record 优先、sealed interface 等）
- [ ] 无硬编码密钥或敏感信息

### 4.2 每个 spec 完成时

- [ ] 所有任务已完成
- [ ] 完整测试套件通过（`mvn test`）
- [ ] 跨模块集成验证通过（详见 #[[file:.kiro/steering/integration-checklist.md]] §2）
- [ ] 代码已合并到 develop
- [ ] feature 分支已清理

### 4.3 每个 Phase 完成时

- [ ] 架构对齐审计通过（详见 #[[file:.kiro/steering/integration-checklist.md]] §3）
- [ ] 架构文档与实际代码一致，偏差已处理
- [ ] 特性文档与实际功能一致，偏差已记录

---

## 5. 异常处理

### 5.1 任务执行失败

- 如果测试不通过，先修复再提交，不提交失败的代码
- 如果发现设计问题需要调整，先更新 spec design 文档，再继续实现
- 如果发现前置模块有缺陷，先在前置模块的 bugfix 分支修复，合并后再继续

### 5.2 Spec 范围变更

- 如果执行过程中发现 scope 过大，可以将剩余任务拆分为新的 spec
- 新 spec 作为当前 spec 的后续，保持依赖关系
- 已完成的任务正常合并，不回滚

---

## 6. 自主执行约定

Kiro 在整个 spec 生命周期中完全自主执行，无需等待用户确认：

- spec 的 requirements / design / tasks 文档创建和更新
- 按 tasks 列表执行开发任务
- git 分支创建、提交、合并（遵循 git-workflow 规范）
- 代码编写、测试编写、bug 修复
- 任务状态更新
- 当前 spec 完成后，自动开始下一个 spec（按模块依赖图顺序）

唯一需要用户介入的情况：遇到无法自行解决的技术障碍（如外部服务不可用、依赖库 bug 等）。

---

## 7. Spec 完成后规划下一个 Spec

每个 spec 的所有任务完成、代码合并到 develop、feature 分支清理后，立即执行以下流程规划下一个 spec。

### 7.1 确定下一个模块

1. 查阅 §1.1 模块依赖图，找到当前已完成模块的下一个模块
2. 确认该模块的所有前置依赖已完成（已合并到 develop）
3. 如果同一 Phase 内有多个无依赖关系的模块可选，按编号顺序选择最小的

### 7.2 规划前准备

1. 阅读下一个模块对应的架构文档（`docs/architecture/{module}.md`）和特性文档（`docs/features/{module}.md`）
2. 检查已完成模块中是否有该模块需要依赖的接口、类型或配置
3. **基于实际源码验证接口**：对所有跨模块依赖，读取实际 Java 源码核对方法签名、record 字段、sealed interface permits（不仅依赖架构文档）
4. 确认 develop 分支上工作区干净

### 7.3 自动启动 Spec 创建

1. 使用 Kiro spec 工作流创建新 spec（requirements → design → tasks）
2. spec 的 feature_name 与模块名一致
3. requirements 文档中引用对应的架构文档和特性文档
4. design 文档聚焦于实现方案，引用已完成模块提供的接口
5. tasks 拆分到可独立提交的粒度

### 7.4 衔接检查清单

- [ ] 上一个 spec 已合并到 develop，feature 分支已删除
- [ ] 下一个模块的前置依赖全部满足
- [ ] 已阅读对应的架构文档和特性文档
- [ ] develop 分支工作区干净
- [ ] 新 spec 目录已创建（`.kiro/specs/{feature_name}/`）
