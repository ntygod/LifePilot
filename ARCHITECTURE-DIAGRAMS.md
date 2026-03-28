# ZhiWei 架构设计图

> **文档性质**：架构可视化文档（Developer-Facing）
> **目标读者**：架构师、开发者、技术评审者
> **最后更新**：2026-03-17
> **关联文档**：[ARCHITECTURE.md](./docs/ARCHITECTURE.md)、[memory-system.md](./docs/architecture/memory-system.md)、[skill-system.md](./docs/architecture/skill-system.md)

---

## 目录

- [1. 系统整体架构图](#1-系统整体架构图)
- [2. 数据流图](#2-数据流图)
- [3. 组件交互图](#3-组件交互图)
- [4. 部署架构图](#4-部署架构图)
- [5. 记忆系统架构图](#5-记忆系统架构图)
- [6. Skill 与工具生态架构图](#6-skill-与工具生态架构图)
- [7. Agent 执行流程时序图](#7-agent-执行流程时序图)
- [8. 主动推理流程时序图](#8-主动推理流程时序图)
- [9. 数据模型关系图](#9-数据模型关系图)
- [10. Gateway 中间件流水线](#10-gateway-中间件流水线)
- [11. LLM 路由与熔断](#11-llm-路由与熔断)
- [12. 可观测性（Trace/Span）视图](#12-可观测性tracespan视图)
- [13. 护栏与权限（Policy-as-Code）视图](#13-护栏与权限policy-as-code视图)
- [14. 故障与恢复（Resilience）视图](#14-故障与恢复resilience视图)

---

## 1. 系统整体架构图

### 1.1 分层架构视图

```mermaid
graph TB
    subgraph "用户交互层 (Presentation)"
        WebUI[zhiwei-web<br/>Vue 3 + Vite + Pinia]
        CLI[CLI<br/>JLine 3]
        IM[IM 渠道<br/>飞书 / 钉钉 / 企微]
    end

    subgraph "网关层 (Gateway)"
        Gateway[MessageGateway]
        Middleware[中间件管道<br/>Auth → RateLimit → Security<br/>→ Router → Execution → Audit]
    end

    subgraph "Agent 引擎层 (Agent Engine)"
        ReactLoop[ReactAgentLoop<br/>ReAct 循环引擎]
        CtxAssembler[ContextAssembler<br/>上下文组装]
        SessionMgr[SessionManager<br/>会话管理]
        Suspend[SuspendStore<br/>挂起/恢复]
    end

    subgraph "能力层 (Capability)"
        SkillSystem[Skill 系统<br/>注册 / 验证 / 激活 / 生成]
        ToolEco[工具生态<br/>BuiltinTool / McpTool]
        MemorySystem[记忆系统<br/>Working / Episodic / Semantic / Procedural]
        KnowledgeBase[知识库<br/>文档解析 / 分块 / 检索 / Rerank]
    end

    subgraph "基础设施层 (Infrastructure)"
        LlmRouter[LlmRouter<br/>场景路由 + 熔断 + 缓存]
        Observability[可观测性<br/>Trace / Guardrail / DataRedactor]
        Workflow[工作流引擎<br/>DAG 调度 / 表达式]
        MultiAgent[多 Agent<br/>Handoff / A2A]
    end

    subgraph "存储层 (Storage)"
        SQLite[(SQLite<br/>WAL + FTS5)]
        SqliteVec[(sqlite-vec<br/>向量索引)]
        FileStore[文件系统<br/>配置 / 日志]
    end

    subgraph "外部服务 (External)"
        DeepSeek[DeepSeek API]
        OpenAI[OpenAI API]
        Qwen[Qwen API]
        Ollama[Ollama 本地]
        McpServers[MCP Servers]
    end

    WebUI --> Gateway
    CLI --> Gateway
    IM --> Gateway
    Gateway --> Middleware
    Middleware --> ReactLoop

    ReactLoop --> CtxAssembler
    ReactLoop --> SessionMgr
    ReactLoop --> Suspend
    CtxAssembler --> MemorySystem
    CtxAssembler --> KnowledgeBase

    ReactLoop --> ToolEco
    ReactLoop --> SkillSystem
    ReactLoop --> LlmRouter

    ToolEco --> McpServers
    LlmRouter --> DeepSeek
    LlmRouter --> OpenAI
    LlmRouter --> Qwen
    LlmRouter --> Ollama

    MemorySystem --> SQLite
    MemorySystem --> SqliteVec
    KnowledgeBase --> SQLite
    KnowledgeBase --> SqliteVec
    Observability --> SQLite
    Workflow --> SQLite
```

### 1.2 模块依赖关系（22 个后端模块）

```mermaid
graph LR
    subgraph "核心引擎"
        agent[agent<br/>ReactAgentLoop]
        llm[llm<br/>LlmRouter]
    end

    subgraph "能力模块"
        memory[memory]
        skill[skill]
        tool[tool]
        knowledge[knowledge]
        meta[meta<br/>元能力]
    end

    subgraph "交互模块"
        interaction[interaction<br/>Gateway + 中间件]
        conversation[conversation]
        notification[notification]
        a2a[a2a<br/>Agent-to-Agent]
    end

    subgraph "扩展模块"
        workflow[workflow]
        multiagent[multiagent]
        mcp[mcp]
        scheduler[scheduler]
        sync[sync]
        media[media]
    end

    subgraph "基础设施"
        observability[observability<br/>Trace + Guardrail]
        config[config]
        datastore[datastore]
        prompt[prompt]
        sandbox[sandbox]
        eval[eval]
        marketplace[marketplace]
    end

    agent --> llm
    agent --> memory
    agent --> tool
    agent --> observability
    agent --> conversation
    agent --> prompt
    agent --> media

    interaction --> agent
    skill --> tool
    meta --> tool
    meta --> skill
    knowledge --> llm
    mcp --> tool
    multiagent --> agent
    workflow --> agent
    scheduler --> skill
```

---

## 2. 数据流图

### 2.1 用户请求处理流程（ReAct 循环）

```mermaid
sequenceDiagram
    participant User as 用户
    participant CH as ChannelAdapter
    participant GW as MessageGateway
    participant MW as 中间件管道
    participant Agent as ReactAgentLoop
    participant Ctx as ContextAssembler
    participant LLM as LlmRouter
    participant Tool as ToolExecutionPipeline
    participant Mem as Memory
    participant Trace as TraceRecorder

    User->>CH: 发送消息
    CH->>GW: normalize → GatewayMessage
    GW->>MW: Auth → RateLimit → Security → Router
    MW->>Agent: ExecutionMiddleware.run()
    
    Agent->>Trace: startTrace()
    Agent->>Ctx: assemble(sessionId, input)
    Ctx->>Mem: 检索相关记忆（WorkingMemory + Retrieval）
    Mem-->>Ctx: 记忆片段 + Token 预算分配
    Ctx-->>Agent: AssembledContext

    loop ReAct 循环（最大 maxSteps）
        Agent->>LLM: getChatModelWithInfo() → Prompt
        LLM-->>Agent: ChatResponse
        Agent->>Trace: recordStep(LlmCallStep)
        
        alt 包含 ToolCall
            Agent->>Agent: 记录 ReactStep.ToolCall
            Agent->>Tool: execute(toolId, params)
            Tool-->>Agent: ToolResult
            Agent->>Agent: 记录 ReactStep.Observation
            Agent->>Trace: recordStep(ToolCallStep)
        else 纯文本回答
            Agent->>Agent: 记录 ReactStep.Answer
        end
    end

    Agent->>Mem: 异步写入情景记忆
    Agent->>Trace: endTrace()
    Agent-->>GW: AgentResponse
    GW->>MW: AuditMiddleware 记录审计日志
    GW-->>CH: GatewayResponse
    CH-->>User: 响应
```

### 2.2 记忆形成与巩固流程

```mermaid
flowchart TD
    Start([用户交互]) --> Extract[RealtimeExtractor<br/>实时实体抽取]
    Extract --> WM[WorkingMemory<br/>会话级临时存储]
    
    WM --> Episodic[ConversationRecord<br/>情景记忆写入]
    
    Episodic --> Pipeline[ConsolidationPipeline<br/>定时 / Idle 触发]
    
    Pipeline --> Semantic[EpisodicToSemanticConsolidator<br/>实体去重 + 关系抽取 + 重要性提升]
    Pipeline --> PrefSync[PreferenceConsolidator<br/>L3 PREFERENCE → L4 PreferenceRule]
    Pipeline --> ExpMerge[ExperienceMerger<br/>相似经验合并为元经验]
    Pipeline --> ExpPromote[经验提升<br/>高频经验 → ProcedureTemplate<br/>importance≥0.8 且 access≥3]
    Pipeline --> Procedural[EpisodicToProceduralConsolidator<br/>模式提取 → ProcedureTemplate]
    
    Semantic --> TE[TemporalEntity<br/>语义记忆]
    Semantic --> TR[TemporalRelation<br/>实体关系]
    ExpMerge --> TE
    ExpPromote --> PT[ProcedureTemplate<br/>程序记忆]
    PrefSync --> PR_T[PreferenceRule<br/>用户偏好]
    Procedural --> PT
    
    subgraph "经验学习（asyncPostProcess）"
        ES_F[ExperienceSummarizer<br/>经验提炼]
        ET_F[EffectivenessTracker<br/>效果评估]
        CL_F[ContrastiveLearner<br/>对比学习]
        SR_F[SubtaskReflector<br/>子任务反思]
    end
    
    ES_F --> TE
    ET_F --> TE
    CL_F --> TE
    SR_F --> TE
    
    TE --> Forgetting[ForgettingStrategy<br/>时间衰减 + 过期归档]
    TR --> Forgetting
    PT --> Forgetting
```

---

## 3. 组件交互图

### 3.1 ReactAgentLoop 核心交互

```mermaid
graph TB
    subgraph "ReactAgentLoop"
        Loop[ReAct 循环]
        State[ReactAgentState<br/>不可变状态快照]
        Steps[ReactStep 序列]
    end

    subgraph "上下文组装"
        Assembler[ContextAssembler]
        WM[WorkingMemory]
        Retrieval[MemoryRetrieval<br/>fusedScore 排序]
        PromptReg[PromptRegistry<br/>模板管理]
    end

    subgraph "LLM 调用"
        Router[LlmRouter]
        ChatModel[ChatModel<br/>手动 tool calling]
        Streaming[StreamingLlmResponse]
    end

    subgraph "工具执行"
        ToolProvider[AgentToolProvider<br/>ToolCallback 适配]
        Pipeline[ToolExecutionPipeline<br/>护栏 → 执行 → 审计]
        Registry[DynamicToolRegistry]
    end

    subgraph "挂起/恢复"
        SuspendStore[SuspendStore]
        SuspendReason[SuspendReason<br/>5 种挂起场景]
        ResumeListener[AgentResumeListener]
    end

    Loop --> Assembler
    Assembler --> WM
    Assembler --> Retrieval
    Assembler --> PromptReg

    Loop --> Router
    Router --> ChatModel

    Loop --> ToolProvider
    ToolProvider --> Pipeline
    Pipeline --> Registry

    Loop --> State
    State --> Steps

    Loop --> SuspendStore
    SuspendStore --> SuspendReason
    ResumeListener --> Loop
```

### 3.2 Skill 系统内部交互

```mermaid
graph LR
    subgraph "Skill 注册"
        BuiltinRegistrar[BuiltinSkillRegistrar<br/>内置 Skill 扫描]
        SkillRegistry[SkillRegistry<br/>运行时注册表]
        Marketplace[SkillMarketplace<br/>市场安装]
    end

    subgraph "Skill 验证（三阶段）"
        Format[FormatValidator<br/>YAML Frontmatter 解析]
        Security[SecurityValidator<br/>工具白名单 + 风险 + 注入检测]
        Sandbox[SandboxValidator<br/>隔离环境一致性]
    end

    subgraph "Skill 执行"
        Activator[SkillActivator<br/>激活 + 深度限制]
        Disclosure[SkillDisclosureTool<br/>load_skill]
        GenTool[SkillGenerationTool<br/>generate_skill]
        Generator[SkillGenerator<br/>LLM 自动生成]
    end

    subgraph "工具层"
        DynRegistry[DynamicToolRegistry]
        BuiltinTool[BuiltinTool<br/>Layer 2]
    end

    BuiltinRegistrar --> SkillRegistry
    Marketplace --> SkillRegistry
    SkillRegistry --> Format
    Format --> Security
    Security --> Sandbox

    Activator --> SkillRegistry
    Disclosure --> Activator
    Disclosure --> DynRegistry
    GenTool --> Generator
    Generator --> SkillRegistry

    BuiltinTool --> DynRegistry
```

---

## 4. 部署架构图

### 4.1 Docker Compose 部署结构

```mermaid
graph TB
    subgraph "Docker Compose"
        subgraph "backend 容器"
            App[zhiwei.jar<br/>Spring Boot 3.5.x / Java 22]
            SQLiteDB[(SQLite WAL<br/>/data/zhiwei.db)]
            VecExt[sqlite-vec<br/>向量扩展]
        end

        subgraph "frontend 容器"
            Nginx[Nginx]
            VueApp[zhiwei-web<br/>Vue 3 SPA]
        end
    end

    subgraph "外部 LLM 服务"
        DS[DeepSeek API]
        OA[OpenAI API]
        QW[Qwen API]
        OL[Ollama 本地<br/>localhost:11434]
    end

    User([用户]) --> Nginx
    Nginx --> VueApp
    VueApp -->|REST / SSE| App
    App --> SQLiteDB
    App --> VecExt
    App --> DS
    App --> OA
    App --> QW
    App --> OL

    Volume[(zhiwei-data<br/>Docker Volume)] --> SQLiteDB
```

### 4.2 运行时目录结构

```
ZhiWei/
├── src/main/java/com/lifepilot/   # 22 个后端模块
│   ├── agent/                      # ReAct Agent 引擎
│   ├── memory/                     # 四层记忆系统
│   ├── skill/                      # Skill 注册/验证/激活/生成
│   ├── tool/                       # 三层工具生态
│   ├── llm/                        # LLM 路由/熔断/缓存
│   ├── interaction/                # Gateway + 中间件 + Web API
│   ├── knowledge/                  # 知识库管理
│   ├── observability/              # Trace + Guardrail + DataRedactor
│   ├── workflow/                   # DAG 工作流引擎
│   ├── multiagent/                 # 多 Agent 协作
│   ├── mcp/                        # MCP 协议支持
│   ├── meta/                       # 元能力（自省/便捷/基础设施）
│   ├── conversation/               # 对话历史管理
│   ├── notification/               # 通知系统
│   ├── scheduler/                  # 定时任务
│   ├── media/                      # 多模态媒体处理
│   ├── prompt/                     # Prompt 模板管理
│   ├── datastore/                  # 通用数据存储
│   ├── eval/                       # Agent 评估框架
│   ├── sync/                       # 外部数据同步
│   ├── sandbox/                    # 沙箱执行
│   ├── marketplace/                # Skill 市场
│   ├── a2a/                        # Agent-to-Agent 协议
│   └── config/                     # 全局配置
├── zhiwei-web/                     # 前端独立项目（Vue 3）
├── src/main/resources/
│   ├── application.yml             # 主配置
│   ├── db/migration/               # Flyway 迁移脚本
│   ├── builtin-skills/             # 内置 Skill 定义
│   └── mcp/                # 内置 MCP 服务器配置
├── docker-compose.yml
└── Dockerfile                      # 多阶段构建
```

---

## 5. 记忆系统架构图

### 5.1 四层记忆架构

```mermaid
graph TB
    subgraph "L1: Working Memory 工作记忆"
        WM[WorkingMemory<br/>会话级临时存储<br/>ConcurrentHashMap]
    end

    subgraph "L2: Episodic Memory 情景记忆"
        CR[ConversationRecord<br/>对话记录]
        IR[InjectionRecordRepository<br/>注入记录 + 反馈]
    end

    subgraph "L3: Semantic Memory 语义记忆"
        TE[TemporalEntity<br/>实体 + 重要性评分 + 访问计数]
        TR[TemporalRelation<br/>实体间关系 + 权重]
        RE[RealtimeExtractor<br/>实时实体抽取]
        Dedup[EntityDeduplicator<br/>实体去重]
    end

    subgraph "L4: Procedural Memory 程序记忆"
        PT[ProcedureTemplate<br/>技能模式模板]
        PR[PreferenceRule<br/>用户偏好规则]
    end

    subgraph "经验学习子系统"
        ES[ExperienceSummarizer<br/>经验提炼]
        ET_T[EffectivenessTracker<br/>效果反馈闭环]
        CL_T[ContrastiveLearner<br/>对比学习]
        SR_T[SubtaskReflector<br/>子任务反思]
        EM_T[ExperienceMerger<br/>经验合并]
    end

    WM -->|会话结束写入| CR
    CR -->|ConsolidationPipeline| TE
    CR -->|ConsolidationPipeline| PT
    RE --> TE
    RE --> TR
    Dedup --> TE

    ES -->|asyncPostProcess| TE
    ET_T -->|importanceScore 调整| TE
    CL_T -->|对比洞察写入| TE
    SR_T -->|子任务经验写入| TE
    EM_T -->|元经验合并| TE
    TE -->|高频经验提升<br/>importance≥0.8 且 access≥3| PT

    WM -.->|检索| WM
    CR -.->|检索| CR
    TE -.->|向量 + FTS5 检索| TE
    TR -.->|关系遍历| TR
    PT -.->|模式匹配| PT
```

### 5.2 记忆检索流程（MemoryRetrieval）

```mermaid
flowchart TD
    Query[查询请求] --> QueryRewrite[QueryRewriter<br/>查询改写]
    QueryRewrite --> MultiRetrieval[多路检索]

    MultiRetrieval --> VectorSearch[向量检索<br/>sqlite-vec 余弦相似度]
    MultiRetrieval --> FTS5Search[全文检索<br/>FTS5 BM25]
    MultiRetrieval --> TemporalSearch[时序检索<br/>时间窗口过滤]

    VectorSearch --> FusedScore[fusedScore 融合<br/>向量分 × α + FTS5 分 × β<br/>+ 时间衰减 + importanceScore]
    FTS5Search --> FusedScore
    TemporalSearch --> FusedScore

    FusedScore --> ExpiredFilter[过期过滤<br/>expiredAt 检查]
    ExpiredFilter --> RelevanceFilter[相关性阈值过滤]
    RelevanceFilter --> BudgetAware[预算感知截断<br/>Token 预算分配]
    BudgetAware --> Return[返回 Top-K 记忆片段]
```

### 5.3 巩固管线（ConsolidationPipeline）

```mermaid
sequenceDiagram
    participant Cron as @Scheduled Cron
    participant Pipeline as ConsolidationPipeline
    participant Semantic as EpisodicToSemanticConsolidator
    participant PrefCon as PreferenceConsolidator
    participant ExpMerger as ExperienceMerger
    participant SM as SemanticMemory
    participant PM as ProceduralMemory
    participant Procedural as EpisodicToProceduralConsolidator
    participant LLM as LlmRouter

    Cron->>Pipeline: scheduledConsolidate()
    
    Pipeline->>Semantic: 1. consolidate()（语义巩固）
    Semantic->>LLM: 实体抽取 + 关系推断
    LLM-->>Semantic: 结构化输出
    Semantic->>Semantic: EntityDeduplicator 去重
    Semantic->>Semantic: importanceScore 提升
    Semantic-->>Pipeline: stats(conversationsAnalyzed, entitiesBoosted)

    Pipeline->>Procedural: 2. consolidate()（程序巩固）
    Procedural->>LLM: 模式提取
    LLM-->>Procedural: ProcedureTemplate
    Procedural-->>Pipeline: stats(conversationsAnalyzed, templatesCreated)

    Pipeline->>PrefCon: 3. consolidate()（偏好同步 L3→L4）
    PrefCon->>SM: 加载 PREFERENCE 实体
    PrefCon->>PM: 同步 PreferenceRule
    PrefCon-->>Pipeline: stats(created, reinforced, deleted)

    Pipeline->>ExpMerger: 3.5 merge()（经验合并）
    ExpMerger->>SM: 加载 EXPERIENCE 实体
    ExpMerger->>LLM: 相似经验 LLM 合并
    ExpMerger->>SM: 写入元经验 + 归档原始
    ExpMerger-->>Pipeline: MergeStats(candidates, merged, skipped)

    Pipeline->>Pipeline: 4. promoteHighFrequencyExperiences()（经验提升 L3→L4）
    Pipeline->>SM: findCurrentByType(EXPERIENCE)
    Pipeline->>Pipeline: 过滤 importanceScore≥0.8 且 accessCount≥3
    Pipeline->>PM: save(ProcedureTemplate)
    Pipeline->>SM: archive(已提升经验)

    Note over Pipeline: 每个阶段异常不阻塞后续阶段（try-catch 隔离）
```

---

## 6. Skill 与工具生态架构图

### 6.1 三层工具架构（ToolContract sealed interface）

```mermaid
graph TB
    subgraph "sealed interface ToolContract"
        direction TB
        TC[ToolContract<br/>id / name / description / riskLevel<br/>inputSchema / outputSchema / budget]
    end

    subgraph "Layer 2: Java Native（优先级最高）"
        BT[BuiltinTool<br/>进程内调用，零序列化开销]
        FileTool[FileToolProvider<br/>文件操作]
        BrowserTool[BrowserToolProvider<br/>浏览器自动化]
        InfraTool[InfraToolProvider<br/>基础设施工具]
        InterTool[InteractionToolProvider<br/>人机交互]
        DisclosureTool[SkillDisclosureTool<br/>load_skill]
        GenTool[SkillGenerationTool<br/>generate_skill]
    end

    subgraph "Layer 1: MCP External（优先级较低）"
        MT[McpTool<br/>MCP 协议远程调用]
        McpReg[McpServerRegistry<br/>服务器发现 + 客户端管理]
        McpDisc[McpServerDiscovery<br/>自动发现]
    end

    TC --> BT
    TC --> MT

    BT --> FileTool
    BT --> BrowserTool
    BT --> InfraTool
    BT --> InterTool
    BT --> DisclosureTool
    BT --> GenTool

    ST --> TodoSkill
    ST --> ScheduleSkill
    ST --> HabitSkill
    ST --> ScheduledTask

    MT --> McpReg
    McpReg --> McpDisc
```

### 6.2 工具元能力分组（ToolCategory）

```mermaid
graph LR
    subgraph "7 大元能力维度"
        P[PERCEPTION 感知<br/>环境感知 / Web 搜索 / 浏览器读取]
        A[ACTION 行动<br/>文件写入 / Shell / 浏览器操作]
        C[COGNITION 认知<br/>精确计算 / think 推理]
        S[STORAGE 存储<br/>DataStore CRUD / 聚合查询]
        I[INTERACTION 交互<br/>选择 / 输入 / 通知]
        IN[INTROSPECTION 自省<br/>list-capabilities / status]
        E[EXTENSION 扩展<br/>Skill 发现 / MCP 安装]
    end
```

### 6.3 Skill 验证三阶段管线

```mermaid
flowchart LR
    Input[SKILL.md 输入] --> F[FormatValidator<br/>YAML Frontmatter 解析]
    F -->|parsedMap| S[SecurityValidator<br/>工具白名单检查<br/>HIGH/CRITICAL 风险拒绝<br/>Prompt 注入检测]
    S -->|通过| SB[SandboxValidator<br/>隔离环境一致性验证]
    SB -->|通过| OK[✅ 注册到 SkillRegistry]

    F -->|格式错误| Fail1[❌ 拒绝加载]
    S -->|安全风险| Fail2[❌ 拒绝加载]
    SB -->|沙箱失败| Fail3[❌ 拒绝加载]
```

---

## 7. Agent 执行流程时序图

### 7.1 ReAct 循环完整流程

```mermaid
sequenceDiagram
    participant Agent as ReactAgentLoop
    participant Ctx as ContextAssembler
    participant LLM as LlmRouter.getChatModelWithInfo()
    participant Tool as ToolExecutionPipeline
    participant Guard as GuardrailEngine
    participant Trace as TraceRecorder
    participant SSE as SseSessionManager

    Agent->>Trace: startTrace(traceId, sessionId, goal)
    Agent->>Ctx: assemble(sessionId, input)
    Ctx-->>Agent: AssembledContext(systemPrompt, memories, tools)

    loop step = 0 → maxSteps
        Agent->>LLM: ChatModel.call(Prompt)<br/>internalToolExecutionEnabled=false
        LLM-->>Agent: ChatResponse

        alt response 包含 tool_calls
            Agent->>Agent: 记录 ReactStep.Thought
            loop 每个 tool_call
                Agent->>Agent: 记录 ReactStep.ToolCall(toolId, inputJson)
                Agent->>Guard: checkToolCall(tool, input)
                Guard-->>Agent: Passed / Blocked / NeedsConfirmation
                
                alt NeedsConfirmation
                    Agent->>Agent: 记录 ReactStep.Suspend(UserConfirmation)
                    Agent-->>SSE: 推送确认请求
                    Note over Agent: 挂起等待用户确认
                else Passed
                    Agent->>Tool: execute(toolId, params)
                    Tool-->>Agent: ToolResult
                    Agent->>Agent: 记录 ReactStep.Observation
                    Agent->>Trace: recordStep(ToolCallStep)
                end
            end
        else 纯文本回答（无 tool_calls）
            Agent->>Agent: 记录 ReactStep.Answer(content)
            Agent->>Trace: endTrace()
            Agent-->>SSE: 推送最终回答
        end
    end
```

### 7.2 Agent 挂起/恢复流程

```mermaid
stateDiagram-v2
    [*] --> Running: ReactAgentLoop.run()
    
    Running --> Suspended: 触发挂起条件
    
    state Suspended {
        [*] --> WorkflowWait: 等待异步工作流
        [*] --> UserConfirmation: 等待用户确认高风险工具
        [*] --> RemoteDelegation: 等待 A2A 远程 Agent
        [*] --> ScheduledWakeup: 定时恢复
        [*] --> ExternalDataWait: 等待外部数据就绪
    }

    Suspended --> Running: AgentResumeListener<br/>接收 ResumePayload
    Running --> Completed: ReactStep.Answer
    Completed --> [*]
```

### 7.3 ReactStep 类型层次

```mermaid
graph TB
    RS[sealed interface ReactStep]
    RS --> Thought[Thought<br/>content: String]
    RS --> ToolCall[ToolCall<br/>toolId + inputJson + latencyMs]
    RS --> Observation[Observation<br/>toolId + success + output + tokensUsed]
    RS --> Answer[Answer<br/>content: String]
    RS --> Suspend[Suspend<br/>reason: SuspendReason + suspendedAt]
    RS --> Resume[Resume<br/>payload: ResumePayload + suspendDuration]
```

---

## 8. 主动推理流程时序图

### 8.1 主动推理触发与执行

```mermaid
sequenceDiagram
    participant Scheduler as ProactiveScheduler
    participant Analyzer as ProactiveAnalyzer
    participant Memory as MemoryRetrieval
    participant LLM as LlmRouter
    participant Skill as SkillActivator
    participant Notify as NotificationService

    Scheduler->>Analyzer: 定期触发分析
    Analyzer->>Memory: 扫描近期记忆模式
    Memory-->>Analyzer: 记忆片段 + 实体关系

    Analyzer->>LLM: 意图识别 + 目标推断
    LLM-->>Analyzer: 推理结果

    alt 发现可主动执行的任务
        Analyzer->>Skill: 激活相关 Skill
        Skill-->>Analyzer: 执行结果
        Analyzer->>Memory: 保存推理结果
        Analyzer->>Notify: 推送通知给用户
    else 无需主动行动
        Analyzer-->>Scheduler: 无操作
    end
```

---

## 9. 数据模型关系图

### 9.1 核心实体关系（基于 Flyway V1 Schema）

```mermaid
erDiagram
    conversations ||--o{ messages : "包含"
    conversations ||--o{ conversation_records : "记录"
    
    temporal_entities ||--o{ temporal_relations : "from_entity"
    temporal_entities ||--o{ temporal_relations : "to_entity"
    
    documents ||--o{ document_chunks : "分块"
    knowledge_bases ||--o{ documents : "包含"
    
    skills ||--o{ skill_audit_log : "审计"
    
    agent_traces ||--o{ agent_trace_steps : "步骤"
    
    conversations {
        TEXT id PK
        TEXT title
        TEXT status
        TEXT created_at
        TEXT updated_at
    }

    messages {
        TEXT id PK
        TEXT conversation_id FK
        TEXT role
        TEXT content
        TEXT content_type
        INTEGER token_count
        TEXT created_at
    }

    temporal_entities {
        TEXT id PK
        TEXT name
        TEXT entity_type
        TEXT description
        BLOB embedding
        REAL importance_score
        INTEGER access_count
        TEXT expired_at
        TEXT created_at
        TEXT updated_at
    }

    temporal_relations {
        TEXT id PK
        TEXT from_entity_id FK
        TEXT to_entity_id FK
        TEXT relation_type
        REAL weight
        TEXT created_at
    }

    procedure_templates {
        TEXT id PK
        TEXT name
        TEXT pattern
        TEXT template_json
        INTEGER usage_count
        TEXT created_at
    }

    skills {
        TEXT id PK
        TEXT name
        TEXT description
        TEXT source
        TEXT definition_json
        INTEGER enabled
        TEXT created_at
        TEXT updated_at
    }

    llm_providers {
        TEXT id PK
        TEXT type
        TEXT api_url
        TEXT model_name
        TEXT scenes
        TEXT capabilities
        INTEGER enabled
        INTEGER priority
        TEXT created_at
    }

    agent_traces {
        TEXT id PK
        TEXT session_id
        TEXT goal
        INTEGER total_steps
        INTEGER success
        TEXT created_at
    }
```

### 9.2 存储与索引架构（SQLite + FTS5 + sqlite-vec）

```mermaid
flowchart TB
    subgraph "SQLite 主库（WAL 模式）"
        T1[conversations / messages<br/>对话历史]
        T2[temporal_entities / temporal_relations<br/>语义记忆]
        T3[procedure_templates / preference_rules<br/>程序记忆]
        T4[skills / skill_audit_log<br/>Skill 管理]
        T5[agent_traces / agent_trace_steps<br/>Trace 追踪]
        T6[llm_providers / circuit_breaker_states<br/>LLM 配置]
        T7[documents / document_chunks<br/>知识库]
        T8[guardrail_logs / gateway_audit_log<br/>审计日志]
        T9[semantic_cache<br/>LLM 语义缓存]
    end

    subgraph "全文索引 (FTS5)"
        F1[temporal_entities_fts<br/>name / description]
        F2[document_chunks_fts<br/>content]
    end

    subgraph "向量索引 (sqlite-vec)"
        V1[temporal_entities.embedding<br/>语义记忆向量]
        V2[document_chunks.embedding<br/>文档块向量]
        V3[semantic_cache.query_embedding<br/>缓存查询向量]
    end

    T2 --> F1
    T7 --> F2
    T2 --> V1
    T7 --> V2
    T9 --> V3
```

---

## 10. Gateway 中间件流水线

### 10.1 请求在中间件中的流转

```mermaid
sequenceDiagram
    participant CH as ChannelAdapter<br/>(CLI / Web / 飞书 / 钉钉 / 企微)
    participant GW as MessageGateway
    participant AUTH as AuthMiddleware<br/>order=100
    participant RL as RateLimitMiddleware<br/>order=200
    participant SEC as SecurityMiddleware<br/>order=300
    participant ROUTER as RouterMiddleware<br/>order=400
    participant EXEC as ExecutionMiddleware<br/>order=500
    participant AUDIT as AuditMiddleware<br/>order=600
    participant Agent as ReactAgentLoop

    CH->>GW: normalize() → GatewayMessage
    GW->>AUTH: 渠道认证（按 ChannelType 分派 AuthStrategy）
    AUTH-->>GW: ok / reject

    GW->>RL: 令牌桶限流（按用户隔离）
    RL-->>GW: ok / retry-after

    GW->>SEC: PromptInjectionDetector<br/>+ SensitiveDataDetector<br/>+ TrustScoreCalculator
    SEC-->>GW: ok / deny

    GW->>ROUTER: 路由到目标 handler
    ROUTER-->>GW: target handler

    GW->>EXEC: 执行 handler
    EXEC->>Agent: run(agentState, input)
    Agent-->>EXEC: AgentResponse
    EXEC-->>GW: response

    GW->>AUDIT: DataRedactor.redact() → 写入审计日志
    AUDIT-->>GW: ack
    GW-->>CH: GatewayResponse → sendResponse()
```

### 10.2 中间件可插拔拓扑（管道模式）

```mermaid
flowchart LR
    In[Inbound<br/>GatewayMessage] --> Auth[Auth<br/>100]
    Auth --> Rate[RateLimit<br/>200]
    Rate --> Sec[Security<br/>300]
    Sec --> Route[Router<br/>400]
    Route --> Exec[Execution<br/>500]
    Exec --> Audit[Audit<br/>600]
    Audit --> Out[Outbound<br/>GatewayResponse]

    style Auth fill:#e8f5e9
    style Rate fill:#e3f2fd
    style Sec fill:#fff3e0
    style Route fill:#f3e5f5
    style Exec fill:#fce4ec
    style Audit fill:#e0f2f1
```

> 所有中间件均可通过 `application.yml` 的 `lifepilot.gateway.middleware.*` 独立启用/禁用和调整 order。

---

## 11. LLM 路由与熔断

### 11.1 路由决策流程

```mermaid
flowchart TD
    Req[LlmRequest] --> ModelName{指定 modelName?}
    
    ModelName -->|是| ByModel[ProviderRegistry.findByModelName<br/>精确匹配 → 包含匹配]
    ModelName -->|否| ByScene[ProviderRegistry.findByScene<br/>场景匹配]
    
    ByModel --> Filter[能力过滤<br/>+ 熔断器过滤]
    ByScene --> Filter
    
    ByScene -->|场景无匹配| Fallback[回退: findByCapability<br/>按能力查找]
    Fallback --> Filter
    
    Filter --> Preferred{有 preferredProviderId?}
    Preferred -->|是| Prioritize[优先排序<br/>preferred 排第一]
    Preferred -->|否| Sort[按 priority 升序]
    
    Prioritize --> Loop
    Sort --> Loop
    
    Loop[故障转移循环] --> Call[调用 ProviderAdapter]
    Call -->|成功| Success[recordSuccess<br/>+ 写入 SemanticCache]
    Call -->|失败| Retry{还有候选?}
    Retry -->|是| Backoff[ExponentialBackoff<br/>500ms × 2^n, 上限 5s]
    Backoff --> Loop
    Retry -->|否| Fail[LlmUnavailableException]
```

### 11.2 熔断器状态机（CircuitState sealed interface）

```mermaid
stateDiagram-v2
    [*] --> Closed: 初始状态
    
    Closed --> Closed: recordSuccess()<br/>重置 consecutiveFailures=0
    Closed --> Open: recordFailure()<br/>consecutiveFailures >= threshold

    Open --> HalfOpen: isCallPermitted()<br/>超过 resetTimeout

    HalfOpen --> Closed: recordSuccess()<br/>探测成功
    HalfOpen --> Open: recordFailure()<br/>探测失败

    note right of Closed: Closed(consecutiveFailures)
    note right of Open: Open(openedAt, failureCount)
    note right of HalfOpen: HalfOpen(transitionedAt)<br/>限制 halfOpenMaxAttempts
```

### 11.3 语义缓存（SemanticCache）

```mermaid
flowchart LR
    Req[LLM 请求] --> Lookup[SemanticCache.lookup<br/>scene + responseFormatKey + prompt]
    Lookup -->|命中| Hit[返回 CacheEntry<br/>LlmResponse.cached()]
    Lookup -->|未命中| Call[调用 Provider]
    Call --> PutAsync[SemanticCache.putAsync<br/>异步写入缓存]
    PutAsync --> Return[返回 LlmResponse]
```

---

## 12. 可观测性（Trace/Span）视图

### 12.1 一次 ReactAgentLoop 的 Trace 树

```mermaid
flowchart TD
    T[TraceRecord<br/>traceId + sessionId + goal] --> S1[LlmCallStep<br/>provider + model + latency + tokens]
    T --> S2[ToolCallStep<br/>toolId + action + input + output + riskLevel]
    T --> S3[GuardrailStep<br/>policyId + checkType + passed + riskLevel + approvalMode]
    T --> S4[LlmCallStep<br/>第二轮 LLM 调用]
    T --> S5[ToolCallStep<br/>第二轮工具调用]
```

### 12.2 TraceStep 类型层次

```mermaid
graph TB
    TS[sealed interface TraceStep<br/>stepIndex + timestamp + duration]
    TS --> LCS[LlmCallStep<br/>provider / model / prompt / response<br/>inputTokens / outputTokens / latencyMs]
    TS --> TCS[ToolCallStep<br/>toolId / action / inputJson / outputJson<br/>success / errorMessage / riskLevel]
    TS --> GS[GuardrailStep<br/>policyId / checkType / passed<br/>reason / riskLevel / approvalMode]
```

### 12.3 关键指标与事件

```mermaid
graph LR
    subgraph "Trace 指标"
        M1[totalSteps]
        M2[totalInputTokens / totalOutputTokens]
        M3[totalDuration]
        M4[toolCallCount]
        M5[guardrailBlockCount]
    end
    subgraph "实时事件（TraceStepEvent）"
        E1[onStep 回调<br/>步骤级实时推送]
        E2[onTraceEnd 回调<br/>Trace 结束通知]
    end
```

---

## 13. 护栏与权限（Policy-as-Code）视图

### 13.1 GuardrailPolicy sealed interface 类型层次

```mermaid
graph TB
    GP[sealed interface GuardrailPolicy<br/>policyId / enabled / priority]
    GP --> TRP[ToolRiskPolicy<br/>toolRiskMapping: Map&lt;String, RiskLevel&gt;<br/>defaultRiskLevel: RiskLevel]
    GP --> BLP[BudgetLimitPolicy<br/>dailyTokenLimit: int]
    GP --> CSP[ContentSafetyPolicy<br/>blockedPatterns: List&lt;String&gt;<br/>sensitiveTopics: List&lt;String&gt;]
    GP --> RLP[RateLimitPolicy<br/>maxCallsPerMinute: int<br/>maxCallsPerHour: int]
    GP --> DRP[DataRedactionPolicy<br/>标记是否对工具 I/O 脱敏]
```

### 13.2 GuardrailResult 决策三态

```mermaid
graph LR
    GR[sealed interface GuardrailResult]
    GR --> Passed[Passed<br/>policyId]
    GR --> Blocked[Blocked<br/>policyId + reason + riskLevel]
    GR --> NC[NeedsConfirmation<br/>policyId + message + approvalMode]
```

### 13.3 GuardrailEngine 决策流程

```mermaid
flowchart TD
    Req[checkToolCall<br/>tool, input] --> WL{tool.id 在白名单?}
    WL -->|是| PassWL[✅ Passed: whitelist]
    WL -->|否| Infra{infrastructure 标签<br/>且 riskLevel == LOW?}
    Infra -->|是| PassInfra[✅ Passed: infrastructure-low-risk]
    Infra -->|否| Loop[遍历 enabledPoliciesSorted<br/>按 priority 升序]

    Loop --> Eval[evaluateToolPolicy<br/>switch policy 类型]

    Eval --> TRP[ToolRiskPolicy<br/>查 toolRiskMapping → RiskLevel<br/>→ toApprovalMode]
    Eval --> BLP[BudgetLimitPolicy<br/>查 daily_token_usage 表]
    Eval --> CSP[ContentSafetyPolicy<br/>正则匹配 blockedPatterns]
    Eval --> RLP[RateLimitPolicy<br/>查 tool_call_log 频率]

    TRP --> Decision{ApprovalMode?}
    BLP --> Decision
    CSP --> Decision
    RLP --> Decision

    Decision -->|AUTO| Continue[继续下一策略]
    Decision -->|AUTO_WITH_AUDIT| AuditPass[✅ Passed + 写审计日志]
    Decision -->|USER_CONFIRM| NeedConfirm[⚠️ NeedsConfirmation<br/>挂起等待用户确认]
    Decision -->|USER_CONFIRM_WITH_VERIFICATION| NeedVerify[⚠️ NeedsConfirmation<br/>确认 + 二次验证]
    Decision -->|阻断| Block[❌ Blocked<br/>reason + riskLevel]

    Continue --> Loop
    Loop -->|所有策略通过| PassAll[✅ Passed: all_policies]

    NeedConfirm --> Audit[writeAuditLog<br/>+ recordGuardrailStep]
    NeedVerify --> Audit
    Block --> Audit
```

> fail-open 策略：单条策略执行异常时视为 Passed，不阻塞后续策略。

### 13.4 RiskLevel → ApprovalMode 映射

```mermaid
graph LR
    LOW[LOW] -->|toApprovalMode| AUTO[AUTO<br/>自动执行]
    MEDIUM[MEDIUM] -->|toApprovalMode| AWA[AUTO_WITH_AUDIT<br/>自动 + 审计]
    HIGH[HIGH] -->|toApprovalMode| UC[USER_CONFIRM<br/>用户确认]
    CRITICAL[CRITICAL] -->|toApprovalMode| UCV[USER_CONFIRM_WITH_VERIFICATION<br/>确认 + 二次验证]

    style LOW fill:#c8e6c9
    style MEDIUM fill:#fff9c4
    style HIGH fill:#ffe0b2
    style CRITICAL fill:#ffcdd2
```

### 13.5 DataRedactor 脱敏管线

```mermaid
flowchart TD
    Input[原始文本] --> Sort[sortedEnabledRules<br/>按 priority 降序排列]
    Sort --> R1[api_key 优先级=110<br/>sk-**** / api_key=****]
    R1 --> R2[phone 优先级=100<br/>138****5678]
    R2 --> R3[id_card 优先级=90<br/>110***********1234]
    R3 --> R4[bank_card 优先级=80<br/>6222****0123]
    R4 --> R5[email 优先级=70<br/>u***@example.com]
    R5 --> R6[ip_address 优先级=60<br/>***.***.***.***]
    R6 --> Custom[自定义规则<br/>registerRule 动态注册]
    Custom --> Output[脱敏后文本]

    subgraph "RedactionRule record"
        RR[name / description / pattern<br/>replacement / priority / enabled]
    end

    subgraph "审计模式"
        Audit[redactWithAudit<br/>→ RedactionAudit<br/>redactedText + appliedRules]
    end
```

> DataRedactor 支持 `redact()`（纯脱敏）和 `redactWithAudit()`（脱敏 + 记录命中规则），Gateway AuditMiddleware 使用后者写入审计日志。

---

## 14. 故障与恢复（Resilience）视图

### 14.1 LLM Provider 故障降级路径

```mermaid
flowchart TD
    Call[LlmRouter.callWithFailover] --> Select[选择候选 Provider 列表<br/>按 priority 排序 + 熔断器过滤]
    Select --> Loop[故障转移循环]

    Loop --> CB{CircuitBreaker<br/>isCallPermitted?}
    CB -->|否（OPEN 且未超时）| Skip[跳过该 Provider]
    CB -->|是| Invoke[调用 ProviderAdapter.call]

    Invoke -->|成功| Success[recordSuccess<br/>→ 重置 consecutiveFailures=0<br/>→ 写入 SemanticCache]
    Invoke -->|异常| Backoff[ExponentialBackoff<br/>初始 500ms × 2^n<br/>上限 5s，最多 2 次]

    Backoff -->|重试成功| Success
    Backoff -->|重试耗尽| RecordFail[recordFailure<br/>consecutiveFailures++]

    RecordFail --> Threshold{达到 failureThreshold?}
    Threshold -->|是| Trip[熔断器跳闸 → OPEN]
    Threshold -->|否| Next

    Trip --> Next[尝试下一个候选 Provider]
    Skip --> Next

    Next -->|还有候选| Loop
    Next -->|候选耗尽| Fail[抛出 LlmUnavailableException]

    style Success fill:#c8e6c9
    style Fail fill:#ffcdd2
    style Trip fill:#ffe0b2
```

### 14.2 熔断器恢复时序

```mermaid
sequenceDiagram
    participant Client as LlmRouter
    participant CB as CircuitBreaker
    participant Provider as ProviderAdapter

    Note over CB: 状态: CLOSED (consecutiveFailures=0)

    Client->>Provider: call() — 失败
    Client->>CB: recordFailure() → consecutiveFailures=1
    Client->>Provider: call() — 失败
    Client->>CB: recordFailure() → consecutiveFailures=2
    Client->>Provider: call() — 失败
    Client->>CB: recordFailure() → consecutiveFailures=3 ≥ threshold

    Note over CB: 状态: OPEN (openedAt=now)

    Client->>CB: isCallPermitted() → false
    Note over Client: 跳过该 Provider，使用其他候选

    Note over CB: 等待 resetTimeout 超时...

    Client->>CB: isCallPermitted() → true (超时，转 HALF_OPEN)
    Note over CB: 状态: HALF_OPEN (限制 halfOpenMaxAttempts)

    Client->>Provider: call() — 探测调用
    alt 探测成功
        Client->>CB: recordSuccess()
        Note over CB: 状态: CLOSED (恢复正常)
    else 探测失败
        Client->>CB: recordFailure()
        Note over CB: 状态: OPEN (重新熔断)
    end
```

### 14.3 SQLite WAL 模式与 Flyway 恢复

```mermaid
flowchart LR
    subgraph "运行时（WAL 模式）"
        Writer[写入线程] -->|WAL 追加| WAL[WAL 文件]
        Reader1[读取线程 1] -->|快照读| DB[(SQLite 主库)]
        Reader2[读取线程 2] -->|快照读| DB
        WAL -->|checkpoint| DB
    end

    subgraph "启动时（Flyway 迁移）"
        Boot[Spring Boot 启动] --> Flyway[Flyway.migrate]
        Flyway -->|V1__init_schema.sql| DB
        Flyway -->|V2__xxx.sql| DB
        Flyway -->|版本号冲突检测| Fail[启动失败 + 日志提示]
    end

    subgraph "PRAGMA 配置"
        P1[journal_mode = WAL]
        P2[synchronous = NORMAL]
        P3[foreign_keys = ON]
        P4[busy_timeout = 5000]
    end
```

> SQLite WAL 模式允许读写并发：写入追加到 WAL 文件，读取使用快照隔离，checkpoint 时合并回主库。Flyway 在启动时自动执行增量迁移，版本号冲突会阻止启动并输出详细日志。

---

> **文档结束**