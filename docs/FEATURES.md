# 知微（ZhiWei）— 特性总览

> **文档性质**：特性总览文档
> **最后更新**：2026-07-26

## 1. 产品定位与核心价值

知微是一个**本地个人助手**，用户是普通人。

用户能感知的只有三件事：

- **更少误打扰** — 主动性由事件驱动，宁可少说一句，不多说一句
- **更准记住** — 分层记忆 + 巩固管线，记忆文件存在你自己的机器上
- **更懂何时该开口** — 任务边界感知，而不是定时轮询

所有优化必须在单机、本地 LLM 可选、token 严格预算的前提下成立。

据此，知微**不做**这些：代码执行、shell、git 等开发者能力（大厂编码 Agent 在这些方向上更强，且与上面三件事无关）；面向用户的评估与训练数据（评估只服务开发期迭代）。

## 2. 特性列表

### 2.1 核心引擎

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| LLM 多模型路由 | 支持多 LLM Provider 动态路由，内置熔断器和故障转移 | [特性](features/llm-router.md) |
| Agent 控制循环 | AgentOrchestrator + ReactAgentLoop ReAct 循环，支持预算控制、挂起/恢复和取消 | [特性](features/agent-engine.md) |
| 工具系统 | ToolContract 统一契约，支持内置工具、YAML 工具、MCP 工具 | [特性](features/tool-ecosystem.md) |
| 工具授权 | 高风险工具按会话 / 工作区 / 任务 / 长期授权，自主任务支持任务级预授权 | [特性](features/permission.md) |
| 安全护栏 | 四级风险分级（LOW/MEDIUM/HIGH/CRITICAL），工具执行前自动检查 | [特性](features/guardrail.md) |
| MCP 协议支持 | Model Context Protocol 客户端，懒连接 + 工具缓存 + 自动发现，桥接外部工具生态 | [特性](features/mcp-support.md) |

### 2.2 记忆与知识

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| 四层记忆系统 | L1 工作记忆 → L2 情景记忆 → L3 语义记忆 + 知识图谱 → L4 程序记忆；含巩固管线、MaRS 遗忘、热摘要、本轮 6 个新能力（staleness / 检索编排 / REM 联想 / eval harness / 注入检测 / MCP Server） | [特性](features/memory-system.md) |
| 知识库管理 | 多格式文档摄入（PDF/Word/Excel/PowerPoint/Markdown/纯文本）、智能分块、多知识库实例 | [特性](features/knowledge-base.md) |
| Prompt 管理 | 模板注册与管理，支持动态 Prompt 组装 | [特性](features/prompt-management.md) |

### 2.3 交互与技能

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| Skill 系统 | Markdown SKILL.md 三级分层（L1 frontmatter + L2 body + L3 references）、四来源（BUILTIN / USER_IMPORTED / MARKETPLACE / AUTO_GENERATED）、`skill.load` 统一激活 | [特性](features/skill-system.md) |
| 预置 Skill | 13 个 BUILTIN Skill（开发者面技能已移除），启动时走统一安装流水线入 skills 表 | [特性](features/preset-skills.md) |
| Skill 自扩展 | `SkillSynthesizer` 驱动 LLM 生成 + 严格校验（拒未知/HIGH/CRITICAL 工具）+ SSE 广播到前端 toast | [特性](features/skill-development.md) |
| 消息网关 | 统一消息入口，6 层中间件管道（Auth→RateLimit→Security→Router→Execution→Audit） | [特性](features/gateway-channels.md) |
| Channel 适配器 | 企业微信 / 钉钉 / 飞书 / Webhook 四个渠道适配 | [特性](features/gateway-channels.md) |
| 对话管理 | 对话历史存储、最近完整轮次读取、完整时间线展示 | [特性](features/conversation.md) |
| 项目工作空间 | 用户显式创建的领域级任务容器，每个项目对应一个 PROJECT 类型 MemorySpace；ISOLATED / SHARED 两种记忆隔离模式 | [架构](architecture/project.md) |
| 自主任务执行 | cron 定时（用户显式提醒） + 自主工作流 | — |
| 主动引擎 | 事件驱动：Signal → Thinker → ThoughtPool → Gatekeeper → ConversationInitiator。输出是对话而非通知；意图级去重，同一件事只说一次；不定时轮询 | — |
| 定时任务全局管理 | `cron_tasks.project_id` 按项目归属；`/scheduled-tasks` 全局管理页（列表 + 项目 tag + 暂停/恢复/删除）。创建入口保持在对话中由 LLM 自然语言触发 | [API 端点](API_ENDPOINTS.md#scheduled-tasks定时任务管理) |
| 通知系统 | 统一通知服务、直接通知、多渠道广播、富媒体支持、通知历史管理 | [特性](features/notification.md) |

### 2.4 高级能力

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| 多模态处理 | 图片预处理、音频处理、文档格式检测（Apache Tika） | [特性](features/multimodal.md) |
| 工作流引擎 | YAML 声明式工作流、四种触发器（Cron/Event/Condition/Signal）、崩溃恢复 | [特性](features/workflow.md) |
| 浏览器自动化 | Playwright 3 种接入模式（LAUNCH/CDP/PERSISTENT）、snapshot 标号扫描、human takeover 人机接管挂起、SSRF 防护、多云 metadata 拦截 | — |
| Web 抓取 | web.fetch 支持 GET/POST/PUT/DELETE/PATCH、自定义 headers/body、HTML Jsoup 解析 + 浏览器渲染回退 | — |
| 外部数据同步 | CalDAV / Todoist / 滴答清单 / Obsidian 连接器、冲突解决策略（规划中） | [规划](planned/external-data-sync-feat.md) |
| Agentic Evals | YAML 场景定义、五维规则评估、LLM-as-a-Judge、JUnit 5 集成 | [特性](features/agentic-evals.md) |

### 2.5 生态与进阶

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| 多 Agent 协作 | AgentRegistry + spawn_workers 并行 Worker 派发，预设专家 Agent | [特性](features/multi-agent.md) |
| 插件市场 | Skill 发布/发现/安装、GitHub 仓库索引、安全审核 | [特性](features/skill-marketplace.md) |
| 元能力 | 便捷指令、基础设施工具 | [特性](features/meta-capabilities.md) |
| 可观测性 | 轨迹记录/查询、数据自动脱敏、轨迹评估 | [特性](features/observability.md) |

### 2.6 Web UI 与桌面客户端

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| Web 对话界面 | SSE 流式响应、A2UI Generative UI 渲染 | [特性](features/web-ui.md) |
| 管理页面 | 知识库管理、Skill/MCP 管理、轨迹回放、工作流管理 | [特性](features/web-ui.md) |
| 桌面客户端 | Tauri 2.x 桌面应用，内嵌 Java 后端管理、启动引导向导、系统托盘 | [架构](architecture/deployment.md) |

### 2.7 部署与运维

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| 部署体验 | Docker 镜像、一键启动脚本、Tauri 桌面安装包、配置版本迁移 | [特性](features/deployment.md) |
| 性能优化 | 对话压缩、Token 预算动态分配、缓存机制 | [规划](planned/performance-optimization-feat.md) |
