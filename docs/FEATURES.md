# ZhiWei — 见微知著，你的 AI 伙伴

> **产品定位**：本地运行的个人 AI Agent 助手
> **核心理念**：不只是被动回答问题，而是主动理解你的行为模式，在合适的时机给出建议和帮助
> **隐私承诺**：所有个人数据存储在本地，你完全掌控自己的隐私

---

## 一、产品简介

### 1.1 ZhiWei 是什么？

ZhiWei 是一个运行在你本地设备上的个人 AI Agent 助手。你可以用自然语言和它对话，让它帮你管理待办、安排日程、养成习惯、整理知识——更重要的是，它会从你的日常交互中学习，逐渐理解你的行为模式，在你需要的时候主动提供帮助。

**一句话概括**：ZhiWei 是一个越用越懂你的 AI 伙伴，而不只是一个聊天机器人。

### 1.2 为什么选择 ZhiWei？

市面上不缺 AI 助手，但大多数产品存在以下问题：

| 痛点 | 传统 AI 助手 | ZhiWei 的解决方案 |
|------|-------------|---------------------|
| 没有记忆 | 每次对话从零开始，不记得你说过什么 | 四层认知记忆系统 + 时序知识图谱，越用越懂你 |
| 被动等待 | 只有你问它才回答 | 主动推理引擎，在合适的时机主动提醒和建议 |
| 隐私担忧 | 数据上传到云端，不知道被如何使用 | 本地优先架构，数据完全存储在你的设备上 |
| 能力固定 | 功能由开发者预设，无法扩展 | 三层混合工具生态 + Skill 自扩展，能力持续增长 |
| 黑盒运行 | 不知道 AI 为什么做出某个决策 | Trace 级可观测性，每个决策都可追溯和回放 |
| 供应商锁定 | 绑定单一 AI 模型 | 支持多 LLM 服务商，智能路由 + 自动故障转移 |

### 1.3 与竞品的核心差异

相比 OpenClaw（180k+ Stars）和 AstrBot（13k+ Stars）等主流 AI Agent 框架：

| 能力维度 | OpenClaw | AstrBot | ZhiWei |
|----------|----------|---------|-----------|
| 记忆系统 | Markdown 文件记忆 | 基础对话历史 | **四层认知记忆 + 时序知识图谱** |
| 检索能力 | 单一检索 | 向量 + BM25 | **向量 + FTS5 + 图遍历 三路混合** |
| 主动智能 | 被动响应 | 被动响应 | **ProactiveReasoner + 智能降频状态机** |
| 状态管理 | 隐式状态 | 事件总线 | **StateReducer 确定性状态机（可测试、可回放）** |
| 可观测性 | 基础日志 | 基础日志 | **Trace 级行为追踪 + 护栏引擎 + 数据脱敏** |
| 隐私设计 | 本地运行 | 偏云端 | **本地优先，敏感数据自动脱敏** |
| 记忆演化 | 无 | 无 | **记忆巩固 + 遗忘策略（认知科学启发）** |
| 工具生态 | MCP 原生 | 900+ 插件 | **三层混合：MCP + YAML 声明式 + Java 原生** |

---

## 二、核心特性

> 各特性的详细说明已拆分为独立文档，便于增量阅读和 Spec 规划。

| 模块 | 功能说明 | 对应架构设计 |
|------|---------|-------------|
| 🧠 智能 Agent 引擎 | [agent-engine.md](features/agent-engine.md) | [architecture/agent-engine.md](architecture/agent-engine.md) |
| 🎯 Agent Skills 技能系统 | [skill-system.md](features/skill-system.md) | [architecture/skill-system.md](architecture/skill-system.md) |
| 🧬 四层认知记忆系统 | [memory-system.md](features/memory-system.md) | [architecture/memory-system.md](architecture/memory-system.md) |
| 🔀 多 LLM 服务商支持 | [llm-router.md](features/llm-router.md) | [architecture/llm-router.md](architecture/llm-router.md) |
| 🔌 MCP 协议支持 | [mcp-support.md](features/mcp-support.md) | — |
| 🧩 混合工具生态 | [tool-ecosystem.md](features/tool-ecosystem.md) | [architecture/tool-ecosystem.md](architecture/tool-ecosystem.md) |
| 📋📅🎯 内置 Skills | [builtin-skills.md](features/builtin-skills.md) | — |
| 📚 文档/知识库管理 | [knowledge-base.md](features/knowledge-base.md) | [architecture/knowledge-base.md](architecture/knowledge-base.md) |
| 🔮🔄 主动推理 + 工作流 | [proactive-reasoning.md](features/proactive-reasoning.md) | — |
| 🚪🛡️💬 Gateway + 安全 + 多渠道 | [gateway-channels.md](features/gateway-channels.md) | [architecture/gateway-middleware.md](architecture/gateway-middleware.md) |
| 🔍🔒 可观测性 + 隐私 | [observability.md](features/observability.md) | [architecture/observability.md](architecture/observability.md) |
| 📦 部署体验 | [deployment.md](features/deployment.md) | — |

---

## 三、配置指南

### 3.1 application.yml 配置结构

```yaml
lifepilot:
  # ==================== 基础配置 ====================
  # 数据存储路径（默认 ~/.zhiwei/）
  data-dir: ~/.zhiwei

  # ==================== LLM 服务商配置 ====================
  llm:
    providers:
      # DeepSeek — 推荐作为主力模型，性价比高
      - id: deepseek-main
        type: deepseek
        api-url: https://api.deepseek.com/v1
        api-key: ${DEEPSEEK_API_KEY}
        model-name: deepseek-chat
        timeout-seconds: 30
        priority: 1
        scenes: [intent_understanding, task_planning, chat]

      # Ollama 本地模型 — 隐私敏感场景
      - id: ollama-local
        type: ollama
        api-url: http://localhost:11434
        model-name: qwen2.5:14b
        timeout-seconds: 60
        priority: 2
        scenes: [knowledge_extraction, chat]

      # 百度文心 — 中文对话备选
      - id: wenxin
        type: wenxin
        api-key: ${WENXIN_API_KEY}
        secret-key: ${WENXIN_SECRET_KEY}
        model-name: ernie-4.0
        timeout-seconds: 30
        priority: 3
        scenes: [chat]

      # 阿里通义千问
      - id: qwen-cloud
        type: qwen
        api-key: ${QWEN_API_KEY}
        model-name: qwen-max
        timeout-seconds: 30
        priority: 4
        scenes: [chat, knowledge_extraction]

      # 智谱 GLM
      - id: glm
        type: glm
        api-key: ${GLM_API_KEY}
        model-name: glm-4
        timeout-seconds: 30
        priority: 5
        scenes: [chat]

  # ==================== MCP Server 配置 ====================
  mcp:
    servers:
      - name: filesystem
        command: npx
        args: ["-y", "@modelcontextprotocol/server-filesystem", "/home/user/documents"]
        transport: stdio
      - name: github
        url: http://localhost:3001/sse
        transport: sse

  # ==================== Skill 配置 ====================
  skills:
    # 用户自定义 Skill 目录
    user-skills-dir: ~/.zhiwei/skills
    # 自生成 Skill 是否需要用户确认
    auto-generated-require-confirmation: true
    # 激活深度限制
    max-activation-depth: 2

  # ==================== 知识库配置 ====================
  knowledge-base:
    bases:
      - id: work-docs
        name: 工作文档库
        embedding-model: text-embedding-v3
        reranker-model: bge-reranker-v2
        chunk-strategy: heading
        chunk-size: 512
        chunk-overlap: 64

      - id: study-notes
        name: 学习笔记库
        embedding-model: ollama/nomic-embed-text
        reranker-model: none
        chunk-strategy: recursive
        chunk-size: 1024
        chunk-overlap: 128

  # ==================== 消息通道配置 ====================
  channels:
    wecom:
      enabled: false
      corp-id: ${WECOM_CORP_ID}
      agent-id: ${WECOM_AGENT_ID}
      secret: ${WECOM_SECRET}
      token: ${WECOM_TOKEN}
      encoding-aes-key: ${WECOM_AES_KEY}

    dingtalk:
      enabled: false
      app-key: ${DINGTALK_APP_KEY}
      app-secret: ${DINGTALK_APP_SECRET}

    feishu:
      enabled: false
      app-id: ${FEISHU_APP_ID}
      app-secret: ${FEISHU_APP_SECRET}

  # ==================== 提醒偏好 ====================
  notification:
    quiet-hours:
      start: "22:00"
      end: "08:00"
    schedule-reminder-minutes: 15
    todo-deadline-hours: 24

  # ==================== 向量存储 ====================
  vector-store:
    type: sqlite-vec    # sqlite-vec（默认）或 chroma
    # Chroma 配置（仅 type=chroma 时生效）
    chroma-url: http://localhost:8000
    chroma-collection: zhiwei-memory

  # ==================== 可观测性 ====================
  observability:
    trace:
      enabled: true
      retention-days: 30    # Trace 保留天数
    guardrail:
      enabled: true
      high-risk-require-confirmation: true
    data-redaction:
      enabled: true
      patterns: [PHONE, ID_CARD, BANK_CARD, EMAIL]
```

### 3.2 环境变量

敏感信息（API 密钥等）建议通过环境变量配置：

```bash
# LLM 服务商密钥
export DEEPSEEK_API_KEY=your-deepseek-api-key
export WENXIN_API_KEY=your-wenxin-api-key
export WENXIN_SECRET_KEY=your-wenxin-secret-key
export QWEN_API_KEY=your-qwen-api-key
export GLM_API_KEY=your-glm-api-key

# 消息平台密钥（按需配置）
export WECOM_CORP_ID=your-corp-id
export WECOM_SECRET=your-secret
export WECOM_AGENT_ID=your-agent-id
export WECOM_TOKEN=your-token
export WECOM_AES_KEY=your-aes-key

export DINGTALK_APP_KEY=your-app-key
export DINGTALK_APP_SECRET=your-app-secret

export FEISHU_APP_ID=your-app-id
export FEISHU_APP_SECRET=your-app-secret
```

> ⚠️ 不要将 API 密钥直接写在 `application.yml` 中，也不要提交到版本控制系统。

---

## 四、快速开始

### 4.1 前置条件

- **Java 22+**（必需）
- **Maven 3.9.x**（必需）
- 至少一个 LLM 服务商的 API Key，或本地安装 [Ollama](https://ollama.ai)

### 4.2 安装与启动

```bash
# 1. 克隆项目
git clone https://github.com/your-username/lifepilot.git
cd lifepilot

# 2. 配置 LLM 服务商
cp src/main/resources/application-example.yml src/main/resources/application.yml
# 编辑 application.yml，填入你的 API Key

# 3. 设置环境变量
export DEEPSEEK_API_KEY=your-api-key

# 4. 启动
mvn spring-boot:run
```

**或使用启动脚本：**

```bash
# Linux / macOS
chmod +x start.sh
./start.sh

# Windows
start.bat
```

**或使用 Docker：**

```bash
docker-compose up -d
```

### 4.3 首次使用

1. **配置 LLM 服务商**（推荐先用 DeepSeek，性价比高）：

```bash
lifepilot llm add
# 按提示输入服务商类型、API Key 等信息
```

2. **验证连通性**：

```bash
lifepilot llm test deepseek-main
# 输出：✅ DeepSeek 连接成功（延迟: 320ms）
```

3. **开始对话**：

```bash
lifepilot chat
```

4. **试试这些命令**：

```
你：你好，我是小明
ZhiWei：你好小明！很高兴认识你。我是 ZhiWei，你的 AI 伙伴。
         有什么我可以帮你的吗？

你：帮我创建一个明天下午3点的会议，和产品团队讨论Q2规划
ZhiWei：✅ 已创建日程「产品团队Q2规划讨论」
         📅 明天 15:00-16:00
         🔔 明天 14:45 会提醒你

你：再帮我加个待办，会前准备Q1数据汇总
ZhiWei：✅ 已创建待办「准备Q1数据汇总」
         🔴 优先级：高
         ⏰ 截止时间：明天 14:30（会议前 30 分钟）

你：看看我今天的安排
ZhiWei：📊 今日概览：
         📅 日程：2 项
           09:00 团队周会
           14:00 客户回访
         📋 待办：4 项未完成（1 项高优先级）
         🎯 习惯：晨跑待打卡
```

---

## 五、Skill 开发指南

> 详细开发指南已拆分为独立文档：[skill-development.md](features/skill-development.md)

---

## 六、功能路线图

> 以下路线图按优先级组织，展示 ZhiWei 的未来演进方向。

### Phase 1 — 基础能力补齐（近期）

| 功能 | 优先级 | 状态 | 说明 |
|------|--------|------|------|
| MCP 协议原生支持 | P0 | ✅ 已实现 | MCP Client + stdio/SSE 传输 + 现有插件桥接 |
| Web UI 完善 | P0 | ✅ 已实现 | Vue 3 + Vite + Pinia + SSE 流式对话 + A2UI Generative UI |
| 文档/知识库管理 | P0 | ✅ 已实现 | 多知识库 + 智能分块 + 混合检索 + 认知记忆融合 |
| Gateway + 中间件管道 | P0 | ✅ 已实现 | 统一消息入口 + 6 层中间件管道（Auth → RateLimit → Security → Router → Execution → Audit） |
| 工具分层安全策略 | P0 | ✅ 已实现 | 三层策略模型（Global → Agent → Tool）+ 风险等级 + 用户确认机制 |
| 部署体验优化 | P0 | ✅ 已实现 | Docker 镜像 + docker-compose + 启动脚本（start.sh / start.bat） |

### Phase 2 — 差异化能力（中期）

| 功能 | 优先级 | 状态 | 说明 |
|------|--------|------|------|
| Agent Skills 技能系统 | P0 | ✅ 已实现 | 统一 Skill 架构 + 三种来源 + SubAgent 激活 |
| Skill 自扩展能力 | P1 | ✅ 已实现 | Agent 运行时自动创建 YAML Skill |
| 声明式 YAML Skill | P1 | ✅ 已实现 | 零代码开发 + 运行时热加载 + 记忆访问 |
| 认知记忆增强工作流 | P1 | ✅ 已实现 | WorkflowEngine 执行引擎 + YAML 声明式工作流 + 触发器（Cron / Event / Condition / Signal） |
| Browser 工具 / 网页信息提取 | P1 | ✅ 已实现 | InfraToolProvider 浏览器工具集（打开 / 点击 / 提取 / 截图） |
| 多模态能力 | P1 | ✅ 已实现 | MediaProcessor 图片预处理 + ProviderCapability 能力声明（Vision / TTS / STT） |
| 外部数据源同步 | P1 | ✅ 已实现 | SyncEngine + CalDAV / Todoist / 滴答清单 / Obsidian 连接器 + 冲突解决策略 |

### Phase 3 — 生态建设（远期）

| 功能 | 优先级 | 状态 | 说明 |
|------|--------|------|------|
| 代码执行沙箱 | P2 | ✅ 已实现 | SandboxBooter 抽象（Process / Docker / Remote）+ CodeValidator 危险操作预检 |
| 配置版本迁移 | P2 | 📋 规划中 | 配置格式变更时自动迁移，用户无感升级 |
| 插件市场 / 社区生态 | P2 | ✅ 已实现 | MarketplaceService + IndexManager + GitHub 仓库索引 + 安全审核 + 版本管理 |
| 移动端适配 | P2 | 📋 规划中 | PWA 或响应式 Web UI |
| 多语言 Skill 运行时 | P2 | 📋 规划中 | 支持 Python / JavaScript Skill（通过沙箱执行） |

### 功能创新度总览

| 功能 | 创新度 | 说明 |
|------|--------|------|
| 四层认知记忆系统 | ⭐ 原创 | 认知科学启发，业界领先的 Agent 记忆架构 |
| 时序知识图谱 | ⭐ 原创 | 实体和关系带时间维度，支持时间旅行查询 |
| 混合检索引擎 | ⭐ 原创 | 向量 + FTS5 + 图遍历 三路融合 |
| 记忆巩固 + 遗忘策略 | ⭐ 原创 | 认知科学启发的记忆生命周期管理 |
| 认知记忆增强工作流 | ⭐ 原创 | 记忆模式驱动的自动化，超越传统 Cron |
| 混合工具生态 | ⭐ 原创 | MCP + YAML + Java 三层统一注册调度 |
| ProactiveReasoner + 降频状态机 | ⭐ 原创 | 两阶段主动推理 + 渐进降频/即时恢复 |
| StateReducer 确定性状态机 | ⭐ 原创 | 概率决策与确定性状态分离，可测试可回放 |
| 多 Agent 协作 | ⭐ 原创 | HandoffTool 委托模式 + SubAgent 独立预算/上下文/模型 |
| A2A 协议支持 | 借鉴+创新 | Google A2A 协议实现 + Agent Card 能力声明 + 跨系统互操作 |
| Agentic Evals 评估框架 | ⭐ 原创 | 五维规则评估 + LLM-as-a-Judge + JUnit 5 集成 |
| Agent Skills 统一架构 | 借鉴+创新 | 融合 OpenClaw Pi 哲学 + AstrBot HandoffTool |
| Gateway + 中间件管道 | 借鉴+改进 | 借鉴 OpenClaw Gateway，适配 Spring Boot |
| 工具分层安全策略 | 借鉴+改进 | 借鉴 OpenClaw 8 层策略，精简为实用 3 层 |
| 文档/知识库管理 | 借鉴+改进 | 融合 RAG 最佳实践 + 四层认知记忆 |
| 代码执行沙箱 | 借鉴+改进 | 多后端抽象（Process / Docker / Remote）+ 护栏集成 |
| 插件市场 | 借鉴+改进 | GitHub 仓库索引 + 安全审核 + 版本管理 |

---

## 附录

### A. 技术栈一览

| 组件 | 技术选型 | 说明 |
|------|---------|------|
| 语言 | Java 22 | 支持 Virtual Threads、Record、Sealed、Pattern Matching |
| 框架 | Spring Boot 3.5.3 | 成熟的企业级框架 |
| AI 集成 | Spring AI 1.1.2 | Spring 生态的 AI 抽象层 |
| 结构化存储 | SQLite (xerial 3.49.x) | 轻量级，本地优先，零配置 |
| 向量存储 | sqlite-vec | SQLite 扩展，无需额外服务 |
| 测试 | JUnit 5 + jqwik 1.9.2 | 单元测试 + 属性测试 |
| 构建 | Maven 3.9.x | 标准化构建工具 |
| 前端 | Vue 3 + Vite + Pinia | 独立项目 zhiwei-web，SSE 流式对话 |
| 数据库迁移 | Flyway | 社区版，支持 SQLite |

### B. 目录结构

```
~/.zhiwei/                       # 用户数据目录
├── data/
│   ├── zhiwei.db                # SQLite 主数据库
│   └── vectors.db               # 向量索引数据库
├── skills/                      # 用户自定义 YAML Skill
│   ├── weather-query.yml
│   └── exchange-rate.yml
├── workflows/                   # 自定义工作流
│   └── weekly-report.yml
├── agents/                      # 自定义 Agent 定义
├── knowledge/                   # 知识库文档
│   ├── work-docs/
│   └── study-notes/
├── logs/                        # 日志文件
├── traces/                      # Trace 记录
└── config/                      # 用户配置
    └── settings.json
```

### C. 包结构

```
com.lifepilot
├── a2a            # A2A 协议：A2A Client/Server、Agent Card 能力声明
├── agent          # Agent 引擎：AgentLoop、StateReducer、ContextAssembler、ProactiveReasoner
├── config         # 全局配置：应用级 Bean 配置
├── conversation   # 对话管理：会话历史存储
├── eval           # 评估框架：BenchmarkScenario、TrajectoryEvaluator、LLM-as-a-Judge
├── guardrail      # 护栏引擎：GuardrailPolicy、风险分级
├── interaction    # 交互层：CLI、Web、消息平台适配
├── knowledge      # 知识库：文档解析、分块、索引
├── llm            # LLM 路由：LlmRouter、CircuitBreaker、ProviderAdapter
├── marketplace    # 插件市场：MarketplaceService、IndexManager、安全审核
├── mcp            # MCP 协议：McpClient、Transport、ToolAdapter、Bridge
├── media          # 多模态：MediaProcessor、格式检测
├── memory         # 记忆系统：四层记忆、知识图谱、混合检索、巩固/遗忘管线
├── meta           # 元能力：InfraToolProvider、CapabilityAggregator、IntrospectionSkill
├── multiagent     # 多 Agent 协作：AgentRegistry、HandoffTool、SubAgent 管理
├── observability  # 可观测性：Trace、DataRedactor
├── prompt         # Prompt 管理：模板加载、变量注入
├── sandbox        # 代码沙箱：SandboxBooter、CodeValidator
├── skill          # 技能系统：SkillRegistry、SkillActivator、YAML Skill、Markdown Skill
├── sync           # 外部同步：SyncEngine、CalDAV/Todoist/Obsidian 连接器
├── tool           # 工具系统：ToolContract、DynamicToolRegistry
└── workflow       # 工作流引擎：触发器、执行器、工作流定义
```

---

> **ZhiWei** — 见微知著，你的 AI 伙伴
> 
> 本地运行 · 隐私优先 · 越用越懂你
