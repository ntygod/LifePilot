# 知微（ZhiWei）— 特性总览

> **文档性质**：特性总览文档
> **最后更新**：2026-04

## 1. 产品定位与核心价值

知微是一个 AI 驱动的个人生活助手，通过自然语言交互帮助用户管理日常事务。与通用 AI 助手不同，知微专注于：

- **长期记忆**：多层记忆系统让助手真正"记住"用户的偏好、习惯和历史
- **自主任务**：支持 cron 定时、主动智能引擎（ProactiveEngine 三级检测 + 8 个行为插件 + 四级投递 + 信任阶梯）和自主工作流
- **本地优先**：单 JAR 部署 + SQLite 存储，数据完全在用户本地，隐私友好
- **可扩展**：Markdown SKILL.md 声明式 Skill 系统 + MCP 协议，能力可按需扩展

## 2. 竞品对比

| 能力 | 知微 | ChatGPT | 通义千问 | Notion AI |
|------|------|---------|---------|-----------|
| 多层记忆系统 | ✅ L1~L4 四层 | ❌ 仅会话内 | ❌ 仅会话内 | ❌ |
| 自主任务执行 | ✅ cron/条件触发 | ❌ | ❌ | ❌ |
| 本地部署 | ✅ 单 JAR | ❌ 云端 | ❌ 云端 | ❌ 云端 |
| 工具扩展 | ✅ MCP + Markdown SKILL.md | ✅ 插件 | ✅ 插件 | ❌ |
| 多 Agent 协作 | ✅ spawn_workers 并行 Worker 派发 | ❌ | ❌ | ❌ |
| 外部数据同步 | 📋 规划中（CalDAV/Todoist 等） | ❌ | ❌ | 部分 |
| 工作流自动化 | ✅ YAML 声明式 | ❌ | ❌ | ❌ |
| 代码执行 | ✅ 沙箱隔离 | ✅ | ✅ | ❌ |
| 桌面客户端 | ✅ Tauri 2.x | ❌ | ❌ | ✅ |

## 3. 特性列表

### 3.1 核心引擎

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| LLM 多模型路由 | 支持多 LLM Provider 动态路由，内置熔断器和故障转移 | [特性](features/llm-router.md) |
| Agent 控制循环 | AgentOrchestrator + ReactAgentLoop ReAct 循环，支持预算控制、挂起/恢复和取消 | [特性](features/agent-engine.md) |
| 工具系统 | ToolContract 统一契约，支持内置工具、YAML 工具、MCP 工具 | [特性](features/tool-ecosystem.md) |
| 工具授权 | 高风险工具按会话 / 工作区 / 任务 / 长期授权，自主任务支持任务级预授权 | [特性](features/permission.md) |
| 安全护栏 | 四级风险分级（LOW/MEDIUM/HIGH/CRITICAL），工具执行前自动检查 | [特性](features/guardrail.md) |
| MCP 协议支持 | Model Context Protocol 客户端，懒连接 + 工具缓存 + 自动发现，桥接外部工具生态 | [特性](features/mcp-support.md) |

### 3.2 记忆与知识

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| 四层记忆系统 | L1 工作记忆 → L2 情景记忆 → L3 语义记忆 + 知识图谱 → L4 程序记忆 | [特性](features/memory-system.md) |
| 记忆进阶能力 | 记忆巩固管线、MaRS 认知遗忘策略、混合检索 | [特性](features/memory-advanced.md) |
| 知识库管理 | 多格式文档摄入（PDF/Word/Excel/PowerPoint/Markdown/纯文本）、智能分块、多知识库实例 | [特性](features/knowledge-base.md) |
| Prompt 管理 | 模板注册与管理，支持动态 Prompt 组装 | [特性](features/prompt-management.md) |

### 3.3 交互与技能

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| Skill 系统 | Markdown SKILL.md 声明式 Skill 定义、热加载、file.read(skill=...) 按需激活 | [特性](features/skill-system.md) |
| 预置 Skill | 26 个种子 Skill，首次启动自动提取到用户目录 | [特性](features/preset-skills.md) |
| Skill 自扩展 | Gap 检测 + Markdown SKILL.md 自动生成 + 三重验证，Agent 自主扩展能力 | [特性](features/skill-development.md) |
| 消息网关 | 统一消息入口，6 层中间件管道（Auth→RateLimit→Security→Router→Execution→Audit） | [特性](features/gateway-channels.md) |
| Channel 适配器 | 企业微信 / 钉钉 / 飞书 / Webhook 四个渠道适配 | [特性](features/gateway-channels.md) |
| 对话管理 | 对话历史存储、最近完整轮次读取、完整时间线展示 | [特性](features/conversation.md) |
| 自主任务执行 | cron 定时 + 主动智能引擎（三级检测管线 + 8 个行为插件 + 四级投递 + 信任阶梯） + 自主工作流 | [架构](architecture/proactive-reminder-engine.md) |
| 通知系统 | 统一通知服务、直接通知、多渠道广播、富媒体支持、通知历史管理 | [特性](features/notification.md) |

### 3.4 高级能力

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| 多模态处理 | 图片预处理、音频处理、文档格式检测（Apache Tika） | [特性](features/multimodal.md) |
| 文档工作空间 | docx / xlsx / pptx 新建 + docx / xlsx 锚点编辑 + 版本链 + diff 卡片 + commit/rollback/discard | [API 端点](API_ENDPOINTS.md#documents文档工作空间) |
| 工作流引擎 | YAML 声明式工作流、四种触发器（Cron/Event/Condition/Signal）、崩溃恢复 | [特性](features/workflow.md) |
| 浏览器自动化 | Playwright 3 种接入模式（LAUNCH/CDP/PERSISTENT）、snapshot 标号扫描、human takeover 人机接管挂起、SSRF 防护、多云 metadata 拦截 | — |
| Web 抓取 | web.fetch 支持 GET/POST/PUT/DELETE/PATCH、自定义 headers/body、HTML Jsoup 解析 + 浏览器渲染回退 | — |
| 代码沙箱 | Process/Docker/Remote 三种沙箱模式、会话复用、危险操作预检 | [特性](features/sandbox.md) |
| 外部数据同步 | CalDAV / Todoist / 滴答清单 / Obsidian 连接器、冲突解决策略（规划中） | [规划](planned/external-data-sync-feat.md) |
| Agentic Evals | YAML 场景定义、五维规则评估、LLM-as-a-Judge、JUnit 5 集成 | [特性](features/agentic-evals.md) |

### 3.5 生态与进阶

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| 多 Agent 协作 | AgentRegistry + spawn_workers 并行 Worker 派发，预设专家 Agent | [特性](features/multi-agent.md) |
| A2A 协议 | Agent-to-Agent 协议 Client/Server 实现，跨系统 Agent 互操作 | [特性](features/a2a-protocol.md) |
| 插件市场 | Skill 发布/发现/安装、GitHub 仓库索引、安全审核 | [特性](features/skill-marketplace.md) |
| 元能力 | 便捷指令、基础设施工具 | [特性](features/meta-capabilities.md) |
| 可观测性 | 轨迹记录/查询、数据自动脱敏、轨迹评估 | [特性](features/observability.md) |

### 3.6 Web UI 与桌面客户端

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| Web 对话界面 | SSE 流式响应、A2UI Generative UI 渲染 | [特性](features/web-ui.md) |
| 管理页面 | 知识库管理、Skill/MCP 管理、轨迹回放、工作流管理 | [特性](features/web-ui.md) |
| 桌面客户端 | Tauri 2.x 桌面应用，内嵌 Java 后端管理、启动引导向导、系统托盘 | [架构](architecture/deployment.md) |

### 3.7 部署与运维

| 特性 | 说明 | 详细文档 |
|------|------|---------|
| 部署体验 | Docker 镜像、一键启动脚本、Tauri 桌面安装包、配置版本迁移 | [特性](features/deployment.md) |
| 性能优化 | 对话压缩、Token 预算动态分配、缓存机制 | [规划](planned/performance-optimization-feat.md) |
