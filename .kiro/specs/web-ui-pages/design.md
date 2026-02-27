# Design Document: Web UI 功能页面

## Overview

模块 19 在 Web UI 框架（模块 18）基础上，实现四个功能管理页面及其对应的后端 REST API。后端采用薄层 Controller 模式，直接委托已有服务层完成业务逻辑；前端采用 Vue 3 + Pinia 状态管理，通过统一 API 客户端与后端通信。同时扩展 A2UI 组件目录，新增 Table、CodeBlock、Progress 三个组件。

参考文档：
- 架构设计：#[[file:docs/architecture/web-ui-pages.md]]
- 特性设计：#[[file:docs/features/web-ui-pages.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 集成检查：#[[file:.kiro/steering/integration-checklist.md]]

## Architecture

### 整体分层

```
┌─────────────────────────────────────────────────────────────┐
│  前端 (lifepilot-web)                                        │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────┐ ┌─────────┐│
│  │KnowledgeBase │ │SkillManage   │ │TraceReplay│ │Workflow ││
│  │View.vue      │ │View.vue      │ │View.vue   │ │Manage   ││
│  └──────┬───────┘ └──────┬───────┘ └─────┬─────┘ │View.vue ││
│         │                │               │        └────┬────┘│
│  ┌──────┴────────────────┴───────────────┴─────────────┴────┐│
│  │              API Client (api/client.ts)                   ││
│  └──────────────────────┬───────────────────────────────────┘│
└─────────────────────────┼───────────────────────────────────┘
                          │ REST / JSON
┌─────────────────────────┼───────────────────────────────────┐
│  后端 (Spring Boot)      │                                   │
│  ┌──────────────────────┴───────────────────────────────────┐│
│  │           WebAutoConfiguration (@Bean 注册)               ││
│  ├──────────────┬──────────────┬──────────────┬─────────────┤│
│  │KnowledgeBase │SkillController│TraceController│Workflow    ││
│  │Controller    │              │              │Controller   ││
│  └──────┬───────┘──────┬───────┘──────┬───────┘──────┬──────┘│
│         │              │              │              │        │
│  ┌──────┴──────┐┌──────┴──────┐┌──────┴──────┐┌─────┴──────┐│
│  │KnowledgeBase││SkillRegistry││TraceQuery   ││Workflow    ││
│  │Manager      ││McpServer    ││Service      ││Engine      ││
│  │DocumentInge.││Registry     ││(新增)       ││Registry    ││
│  │(模块 8)     ││DynamicTool  ││             ││Repository  ││
│  │             ││Registry     ││             ││(模块 15)   ││
│  │             ││(模块 4/10)  ││             ││            ││
│  └─────────────┘└─────────────┘└─────────────┘└────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### 设计原则

1. **薄层 Controller**：Controller 只做 HTTP 协议转换（请求解析 → 服务调用 → 响应封装），不包含业务逻辑
2. **Bean 注册模式**：所有新增 Controller 和 Service 通过 `WebAutoConfiguration` 的 `@Bean` 方法注册，与模块 18 保持一致
3. **统一异常处理**：复用已有 `WebExceptionHandler`，新增 `KnowledgeBaseNotFoundException` 和 `DocumentNotFoundException` 的 404 映射
4. **前后端分离**：前端通过 API 客户端封装调用后端 REST API，类型定义在 `types/index.ts` 中扩展

## Components and Interfaces

### 后端新增组件

#### 1. TraceQueryService

新增轨迹查询服务，从 SQLite 读取 `agent_traces` 和 `agent_trace_steps` 表数据。TraceRecorder 仅负责写入，无查询 API。

```java
package com.lifepilot.interaction.web.service;

/**
 * 轨迹查询服务，从 SQLite 读取 Agent 执行轨迹数据。
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceQueryService {

    private final JdbcTemplate jdbcTemplate;

    /** 分页查询轨迹列表，按 created_at 倒序。 */
    public PageResult<TraceRecord> listTraces(int page, int size);

    /** 查询单条轨迹详情。 */
    public Optional<TraceRecord> getTrace(String traceId);

    /** 查询轨迹的所有步骤，按 step_index 升序。 */
    public List<TraceStepRecord> getTraceSteps(String traceId);
}
```

数据模型（record）：

```java
/** 轨迹查询结果。 */
public record TraceRecord(
    String id, String sessionId, String userMessage,
    @Nullable String finalOutput, boolean success,
    @Nullable String errorMessage, @Nullable String terminationReason,
    int totalSteps, int totalTokens, long durationMs,
    @Nullable String modelId, @Nullable String parentTraceId,
    int depth, Instant createdAt
) {}

/** 轨迹步骤查询结果。 */
public record TraceStepRecord(
    String id, String traceId, int stepIndex,
    String phaseBefore, String phaseAfter,
    String actionType, @Nullable String actionJson,
    @Nullable String toolId, @Nullable String toolInputJson,
    @Nullable String toolOutput, boolean success,
    boolean blocked, @Nullable String blockReason,
    int tokensUsed, long latencyMs, Instant createdAt
) {}

/** 分页结果包装。 */
public record PageResult<T>(
    List<T> items, int page, int size, long total
) {}
```

#### 2. KnowledgeBaseController

```java
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseManager kbManager;
    private final DocumentIngester documentIngester;

    // GET  /                          → listKnowledgeBases()
    // POST /                          → createKnowledgeBase(CreateKbRequest)
    // GET  /{id}                      → getKnowledgeBase(id)
    // DELETE /{id}                    → deleteKnowledgeBase(id)
    // GET  /{id}/documents            → listDocuments(id)
    // POST /{id}/documents            → uploadDocument(id, MultipartFile)
    // DELETE /{id}/documents/{docId}  → removeDocument(docId)
}
```

请求/响应 record：

```java
/** 创建知识库请求。 */
public record CreateKbRequest(String name, String description, @Nullable String embeddingModel) {}
```

文件上传处理流程：
1. 接收 `MultipartFile`
2. 校验文件扩展名（.pdf / .docx / .md / .txt）
3. 保存到临时目录 `Files.createTempFile()`
4. 调用 `DocumentIngester.ingest(kbId, tempPath)` 异步处理
5. 返回 202 Accepted + 文档初始状态

#### 3. SkillController

```java
@RestController
@RequestMapping("/api")
public class SkillController {

    private final SkillRegistry skillRegistry;
    private final McpServerRegistry mcpServerRegistry;
    private final DynamicToolRegistry toolRegistry;

    // Skill 端点
    // GET    /skills          → listAll()
    // GET    /skills/{id}     → find(id)
    // DELETE /skills/{id}     → unregister(id)

    // MCP Server 端点
    // GET  /mcp/servers                    → listServers()
    // GET  /mcp/servers/{name}             → getServer(name)
    // POST /mcp/servers/{name}/connect     → connectServer(config)
    // POST /mcp/servers/{name}/disconnect  → disconnectServer(name)
    // GET  /mcp/servers/{name}/tools       → 过滤 getAllTools() 中属于该 server 的工具
}
```

MCP Server 工具查询策略：`DynamicToolRegistry` 的 `serverToolIndex` 是 private 的，无公开方法按 server 查询工具。解决方案：在 `DynamicToolRegistry` 中新增 `getToolsByServer(String serverName)` 方法，返回 `List<ToolContract>`。

```java
/** 获取指定 MCP Server 注册的工具列表。 */
public List<ToolContract> getToolsByServer(String serverName) {
    List<String> toolIds = serverToolIndex.getOrDefault(serverName, List.of());
    return toolIds.stream()
            .map(tools::get)
            .filter(Objects::nonNull)
            .toList();
}
```

#### 4. TraceController

```java
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceQueryService traceQueryService;

    // GET /              → listTraces(page, size)
    // GET /{id}          → getTrace(id)
    // GET /{id}/steps    → getTraceSteps(id)
}
```

#### 5. WorkflowController

```java
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowRegistry workflowRegistry;
    private final WorkflowEngine workflowEngine;
    private final WorkflowRepository workflowRepository;

    // GET  /                      → listAll()
    // GET  /{id}                  → find(id)
    // POST /{id}/enable           → enable(id)
    // POST /{id}/disable          → disable(id)
    // POST /{id}/trigger          → execute(id, inputs)
    // GET  /{id}/executions       → findInstancesByWorkflowId(id)
}
```

触发请求 record：

```java
/** 手动触发工作流请求。 */
public record TriggerWorkflowRequest(@Nullable Map<String, Object> inputs) {}
```

### 后端接口扩展

#### SkillRegistry.listAll()

在 `SkillRegistry` 中新增方法：

```java
/**
 * 返回所有已注册 Skill 的完整定义列表。
 *
 * @return 不可变 Skill 定义列表
 */
public List<SkillDefinition> listAll() {
    return List.copyOf(skills.values());
}
```

#### WorkflowRepository.findInstancesByWorkflowId()

在 `WorkflowRepository` 中新增方法：

```java
/**
 * 按工作流 ID 查询执行实例，按 created_at 倒序。
 *
 * @param workflowId 工作流 ID
 * @return 执行实例列表
 */
public List<WorkflowInstance> findInstancesByWorkflowId(String workflowId) {
    return jdbcTemplate.query(
        "SELECT * FROM workflow_instances WHERE workflow_id = ? ORDER BY created_at DESC",
        this::mapInstance, workflowId
    );
}
```

#### DynamicToolRegistry.getToolsByServer()

在 `DynamicToolRegistry` 中新增方法（如上所述）。

### WebExceptionHandler 扩展

新增对知识库和文档 NotFoundException 的 404 映射：

```java
@ExceptionHandler(KnowledgeBaseNotFoundException.class)
public ResponseEntity<ErrorResponse> handleKbNotFound(KnowledgeBaseNotFoundException ex) {
    log.warn("知识库不存在: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
        new ErrorResponse(404, ex.getMessage(), Instant.now()));
}

@ExceptionHandler(DocumentNotFoundException.class)
public ResponseEntity<ErrorResponse> handleDocNotFound(DocumentNotFoundException ex) {
    log.warn("文档不存在: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
        new ErrorResponse(404, ex.getMessage(), Instant.now()));
}
```

### WebAutoConfiguration 扩展

新增 Bean 注册：

```java
@Bean
@ConditionalOnBean(JdbcTemplate.class)
public TraceQueryService traceQueryService(JdbcTemplate jdbcTemplate) {
    log.info("注册 TraceQueryService");
    return new TraceQueryService(jdbcTemplate);
}

@Bean
@ConditionalOnBean(KnowledgeBaseManager.class)
public KnowledgeBaseController knowledgeBaseController(
        KnowledgeBaseManager kbManager, DocumentIngester documentIngester) {
    log.info("注册 KnowledgeBaseController");
    return new KnowledgeBaseController(kbManager, documentIngester);
}

@Bean
@ConditionalOnBean({SkillRegistry.class, McpServerRegistry.class})
public SkillController skillController(
        SkillRegistry skillRegistry, McpServerRegistry mcpServerRegistry,
        DynamicToolRegistry toolRegistry) {
    log.info("注册 SkillController");
    return new SkillController(skillRegistry, mcpServerRegistry, toolRegistry);
}

@Bean
@ConditionalOnBean(TraceQueryService.class)
public TraceController traceController(TraceQueryService traceQueryService) {
    log.info("注册 TraceController");
    return new TraceController(traceQueryService);
}

@Bean
@ConditionalOnBean({WorkflowRegistry.class, WorkflowEngine.class})
public WorkflowController workflowController(
        WorkflowRegistry workflowRegistry, WorkflowEngine workflowEngine,
        WorkflowRepository workflowRepository) {
    log.info("注册 WorkflowController");
    return new WorkflowController(workflowRegistry, workflowEngine, workflowRepository);
}
```

### 前端新增组件

#### 页面视图

| 文件 | 路由 | 说明 |
|------|------|------|
| `views/KnowledgeBaseView.vue` | `/knowledge-bases` | 知识库管理页 |
| `views/SkillManageView.vue` | `/skills` | Skill / MCP 管理页 |
| `views/TraceReplayView.vue` | `/traces` | 轨迹回放页 |
| `views/WorkflowManageView.vue` | `/workflows` | 工作流管理页 |

#### Pinia Store

| 文件 | 说明 |
|------|------|
| `stores/knowledgeBase.ts` | 知识库列表、当前知识库、文档列表状态管理 |
| `stores/skill.ts` | Skill 列表、MCP Server 列表状态管理 |
| `stores/trace.ts` | 轨迹列表、当前轨迹、步骤列表状态管理 |
| `stores/workflow.ts` | 工作流列表、当前工作流、执行历史状态管理 |

#### API 客户端扩展

在 `api/client.ts` 中新增四组 API 封装：

```typescript
/** 知识库管理 API */
export const knowledgeBaseApi = {
  list(): Promise<KnowledgeBase[]>,
  create(req: CreateKbRequest): Promise<KnowledgeBase>,
  get(id: string): Promise<KnowledgeBase>,
  delete(id: string): Promise<void>,
  listDocuments(kbId: string): Promise<KbDocument[]>,
  uploadDocument(kbId: string, file: File): Promise<KbDocument>,
  deleteDocument(kbId: string, docId: string): Promise<void>,
}

/** Skill 管理 API */
export const skillApi = {
  list(): Promise<SkillSummary[]>,
  get(id: string): Promise<SkillDetail>,
  unregister(id: string): Promise<void>,
}

/** MCP Server 管理 API */
export const mcpApi = {
  listServers(): Promise<McpServer[]>,
  getServer(name: string): Promise<McpServer>,
  connect(name: string): Promise<void>,
  disconnect(name: string): Promise<void>,
  listTools(name: string): Promise<McpTool[]>,
}

/** 轨迹查询 API */
export const traceApi = {
  list(page?: number, size?: number): Promise<PageResult<TraceItem>>,
  get(id: string): Promise<TraceDetail>,
  getSteps(id: string): Promise<TraceStep[]>,
}

/** 工作流管理 API */
export const workflowApi = {
  list(): Promise<WorkflowItem[]>,
  get(id: string): Promise<WorkflowDetail>,
  enable(id: string): Promise<void>,
  disable(id: string): Promise<void>,
  trigger(id: string, inputs?: Record<string, unknown>): Promise<WorkflowExecution>,
  listExecutions(id: string): Promise<WorkflowExecution[]>,
}
```

文件上传使用 `FormData`（不设置 Content-Type，让浏览器自动设置 multipart boundary）：

```typescript
uploadDocument(kbId: string, file: File): Promise<KbDocument> {
  const formData = new FormData()
  formData.append('file', file)
  return fetch(`${BASE}/knowledge-bases/${kbId}/documents`, {
    method: 'POST',
    body: formData
  }).then(handleResponse)
}
```

#### A2UI 扩展组件

| 组件 | 文件 | Props | 注册 type |
|------|------|-------|-----------|
| Table | `A2uiTable.vue` | `columns: {key, label}[]`, `rows: Record<string, unknown>[]` | `"Table"` |
| CodeBlock | `A2uiCodeBlock.vue` | `language?: string`, `code: string` | `"CodeBlock"` |
| Progress | `A2uiProgress.vue` | `value: number`, `label?: string` | `"Progress"` |

CodeBlock 语法高亮方案：使用 [Shiki](https://shiki.style/)（轻量、支持 VS Code 主题、Tree-shakeable）。若 `language` 未指定，以纯文本渲染。

#### 路由扩展

```typescript
// router/index.ts 新增路由
{ path: '/knowledge-bases', name: 'knowledgeBases', component: () => import('@/views/KnowledgeBaseView.vue') },
{ path: '/skills', name: 'skills', component: () => import('@/views/SkillManageView.vue') },
{ path: '/traces', name: 'traces', component: () => import('@/views/TraceReplayView.vue') },
{ path: '/workflows', name: 'workflows', component: () => import('@/views/WorkflowManageView.vue') },
```

#### 侧边栏导航扩展

在 `Sidebar.vue` 底部导航区域新增四个导航项（知识库、技能、轨迹、工作流），使用 `router-link` 并高亮当前活跃路由。

### 依赖接口验证

| 接口 | 源码位置 | 验证状态 |
|------|---------|---------|
| KnowledgeBaseManager.listKnowledgeBases() | com.lifepilot.knowledge.KnowledgeBaseManager | ✅ 已核对 |
| KnowledgeBaseManager.createKnowledgeBase() | com.lifepilot.knowledge.KnowledgeBaseManager | ✅ 已核对 |
| KnowledgeBaseManager.getKnowledgeBase() | com.lifepilot.knowledge.KnowledgeBaseManager | ✅ 已核对 |
| KnowledgeBaseManager.deleteKnowledgeBase() | com.lifepilot.knowledge.KnowledgeBaseManager | ✅ 已核对 |
| KnowledgeBaseManager.listDocuments() | com.lifepilot.knowledge.KnowledgeBaseManager | ✅ 已核对 |
| KnowledgeBaseManager.removeDocument() | com.lifepilot.knowledge.KnowledgeBaseManager | ✅ 已核对 |
| DocumentIngester.ingest(kbId, filePath) | com.lifepilot.knowledge.ingest.DocumentIngester | ✅ 已核对 |
| SkillRegistry.find() / unregister() / listSummaries() | com.lifepilot.skill.registry.SkillRegistry | ✅ 已核对 |
| SkillRegistry.listAll() | com.lifepilot.skill.registry.SkillRegistry | ❌ 不存在，需新增 |
| McpServerRegistry.listServers() / getServer() / connectServer() / disconnectServer() | com.lifepilot.mcp.registry.McpServerRegistry | ✅ 已核对 |
| DynamicToolRegistry.getAllTools() | com.lifepilot.tool.registry.DynamicToolRegistry | ✅ 已核对 |
| DynamicToolRegistry.getToolsByServer() | com.lifepilot.tool.registry.DynamicToolRegistry | ❌ 不存在，需新增 |
| WorkflowRegistry.listAll() / find() / enable() / disable() | com.lifepilot.workflow.registry.WorkflowRegistry | ✅ 已核对 |
| WorkflowEngine.execute() | com.lifepilot.workflow.engine.WorkflowEngine | ✅ 已核对 |
| WorkflowRepository.findInstancesByWorkflowId() | com.lifepilot.workflow.repository.WorkflowRepository | ❌ 不存在，需新增 |
| TraceRecorder（写入 agent_traces / agent_trace_steps） | com.lifepilot.agent.trace.TraceRecorder | ✅ 已核对表结构 |
| WebAutoConfiguration（@Bean 注册模式） | com.lifepilot.interaction.web.config.WebAutoConfiguration | ✅ 已核对 |
| WebExceptionHandler | com.lifepilot.interaction.web.controller.WebExceptionHandler | ✅ 已核对 |

### 跨模块接口变更

| 变更接口 | 所属模块 | 变更内容 | 影响模块 | 说明 |
|---------|---------|---------|---------|------|
| SkillRegistry.listAll() | skill (模块 10) | 新增方法 | web-ui-pages | 新增，无破坏性 |
| WorkflowRepository.findInstancesByWorkflowId() | workflow (模块 15) | 新增方法 | web-ui-pages | 新增，无破坏性 |
| DynamicToolRegistry.getToolsByServer() | tool (模块 3) | 新增方法 | web-ui-pages | 新增，无破坏性 |
| WebExceptionHandler | interaction (模块 18) | 新增 NotFoundException 处理 | web-ui-pages | 新增 handler，无破坏性 |

## Data Models

### 后端 Record（新增）

```java
// com.lifepilot.interaction.web.model 包

/** 创建知识库请求。 */
public record CreateKbRequest(String name, String description, @Nullable String embeddingModel) {}

/** 手动触发工作流请求。 */
public record TriggerWorkflowRequest(@Nullable Map<String, Object> inputs) {}

/** 分页结果包装。 */
public record PageResult<T>(List<T> items, int page, int size, long total) {}

// com.lifepilot.interaction.web.service 包

/** 轨迹查询结果。 */
public record TraceRecord(
    String id, String sessionId, String userMessage,
    @Nullable String finalOutput, boolean success,
    @Nullable String errorMessage, @Nullable String terminationReason,
    int totalSteps, int totalTokens, long durationMs,
    @Nullable String modelId, @Nullable String parentTraceId,
    int depth, Instant createdAt
) {}

/** 轨迹步骤查询结果。 */
public record TraceStepRecord(
    String id, String traceId, int stepIndex,
    String phaseBefore, String phaseAfter,
    String actionType, @Nullable String actionJson,
    @Nullable String toolId, @Nullable String toolInputJson,
    @Nullable String toolOutput, boolean success,
    boolean blocked, @Nullable String blockReason,
    int tokensUsed, long latencyMs, Instant createdAt
) {}
```

### 前端 TypeScript 类型（新增）

```typescript
// types/index.ts 扩展

/** 知识库 */
export interface KnowledgeBase {
  id: string
  name: string
  description: string
  embeddingModel: string
  documentCount: number
  totalChunks: number
  createdAt: string
  updatedAt: string
}

/** 创建知识库请求 */
export interface CreateKbRequest {
  name: string
  description: string
  embeddingModel?: string
}

/** 知识库文档 */
export interface KbDocument {
  id: string
  knowledgeBaseId: string
  fileName: string
  fileSize: number
  mimeType: string
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'ERROR'
  chunkCount: number
  errorMessage?: string
  createdAt: string
  updatedAt: string
}

/** Skill 摘要 */
export interface SkillSummary {
  id: string
  name: string
  description: string
  version: string
  sourceType: 'Builtin' | 'UserDefined' | 'AutoGenerated'
}

/** Skill 详情 */
export interface SkillDetail extends SkillSummary {
  systemPrompt: string
  allowedTools: string[]
  metadata: Record<string, string>
  preferredProviderId?: string
}

/** MCP Server */
export interface McpServer {
  name: string
  state: 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING'
  toolCount: number
  connectedSince?: string
  lastError?: string
}

/** MCP 工具 */
export interface McpTool {
  id: string
  name: string
  description: string
}

/** 轨迹列表项 */
export interface TraceItem {
  id: string
  sessionId: string
  userMessage: string
  success: boolean
  totalSteps: number
  totalTokens: number
  durationMs: number
  createdAt: string
}

/** 轨迹详情 */
export interface TraceDetail extends TraceItem {
  finalOutput?: string
  errorMessage?: string
  terminationReason?: string
  modelId?: string
}

/** 轨迹步骤 */
export interface TraceStep {
  id: string
  stepIndex: number
  phaseBefore: string
  phaseAfter: string
  actionType: string
  actionJson?: string
  toolId?: string
  toolInputJson?: string
  toolOutput?: string
  success: boolean
  blocked: boolean
  blockReason?: string
  tokensUsed: number
  latencyMs: number
  createdAt: string
}

/** 分页结果 */
export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

/** 工作流列表项 */
export interface WorkflowItem {
  id: string
  name: string
  description: string
  enabled: boolean
  triggerTypes: string[]
  version: string
}

/** 工作流详情 */
export interface WorkflowDetail extends WorkflowItem {
  triggers: unknown[]
  inputs: Record<string, unknown>
  steps: unknown[]
  metadata: Record<string, string>
}

/** 工作流执行记录 */
export interface WorkflowExecution {
  id: string
  workflowId: string
  state: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  currentStepIndex: number
  startedAt?: string
  completedAt?: string
  failureReason?: string
  createdAt: string
}
```


## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: KnowledgeBaseController 透传一致性

*For any* KnowledgeBaseManager 返回的知识库列表、知识库详情或文档列表，KnowledgeBaseController 对应的 GET 端点应返回与 Manager 完全一致的数据（JSON 序列化后相等）。

**Validates: Requirements 1.1, 1.3, 1.6**

### Property 2: 不支持的文件格式被拒绝

*For any* 文件扩展名不在 {.pdf, .docx, .md, .txt} 集合中的上传请求，KnowledgeBaseController 应返回 HTTP 400 状态码。

**Validates: Requirements 1.9**

### Property 3: Builtin Skill 不可注销

*For any* SkillSource 为 Builtin 类型的 Skill，调用 DELETE `/api/skills/{id}` 应返回 HTTP 400 状态码，且 SkillRegistry 中该 Skill 仍然存在。

**Validates: Requirements 2.5**

### Property 4: MCP Server 工具查询一致性

*For any* 已连接的 MCP Server，GET `/api/mcp/servers/{name}/tools` 返回的工具列表应与 DynamicToolRegistry.getToolsByServer(name) 返回的工具集合完全一致。

**Validates: Requirements 3.5**

### Property 5: 轨迹列表按时间倒序排列

*For any* 包含多条轨迹记录的数据库，GET `/api/traces` 返回的列表中，每条记录的 createdAt 应大于等于其后一条记录的 createdAt。

**Validates: Requirements 4.2**

### Property 6: 轨迹步骤按 stepIndex 升序排列

*For any* 包含多个步骤的轨迹，GET `/api/traces/{id}/steps` 返回的步骤列表中，每个步骤的 stepIndex 应严格小于其后一个步骤的 stepIndex。

**Validates: Requirements 4.4**

### Property 7: 禁用工作流不可触发

*For any* enabled 为 false 的工作流，调用 POST `/api/workflows/{id}/trigger` 应返回 HTTP 400 状态码，且 WorkflowEngine.execute() 不被调用。

**Validates: Requirements 5.8**

### Property 8: SkillRegistry.listAll() 完整性

*For any* 通过 register() 注册的 N 个 SkillDefinition，listAll() 返回的列表长度应等于 N，且包含所有已注册的 SkillDefinition。

**Validates: Requirements 7.1**

### Property 9: SkillRegistry.listAll() 不可变性

*For any* listAll() 返回的列表，对其执行 add/remove/set 操作应抛出 UnsupportedOperationException。

**Validates: Requirements 7.3**

### Property 10: WorkflowRepository.findInstancesByWorkflowId() 按时间倒序

*For any* 包含多个执行实例的工作流 ID，findInstancesByWorkflowId() 返回的列表中，每条记录的 createdAt 应大于等于其后一条记录的 createdAt。

**Validates: Requirements 8.1**

### Property 11: Progress 值钳制

*For any* 数值 value，A2UI_Progress 组件渲染时使用的实际值应等于 `Math.max(0, Math.min(100, value))`。

**Validates: Requirements 15.5**

### Property 12: API 客户端错误处理

*For any* HTTP 响应状态码不在 200-299 范围内的响应，API 客户端的 request() 函数应抛出包含错误码和错误消息的异常对象。

**Validates: Requirements 17.6**

### Property 13: TraceQueryService 写入-读取一致性

*For any* 通过 TraceRecorder 持久化的轨迹记录，TraceQueryService.getTrace() 应能读取到相同的 traceId、sessionId、userMessage、totalSteps、totalTokens 和 success 字段值。

**Validates: Requirements 4.1, 4.3**

## Error Handling

### 后端异常处理策略

| 异常类型 | HTTP 状态码 | 处理方式 |
|---------|------------|---------|
| `KnowledgeBaseNotFoundException` | 404 | WebExceptionHandler 新增 handler |
| `DocumentNotFoundException` | 404 | WebExceptionHandler 新增 handler |
| `IllegalArgumentException`（name 为空、文件格式不支持等） | 400 | 复用已有 handler |
| Skill 不存在（find 返回 empty） | 404 | Controller 内手动抛出 `IllegalArgumentException` 或返回 404 ResponseEntity |
| MCP Server 不存在（getServer 返回 empty） | 404 | Controller 内返回 404 ResponseEntity |
| 工作流不存在（find 返回 empty） | 404 | Controller 内返回 404 ResponseEntity |
| 轨迹不存在（getTrace 返回 empty） | 404 | Controller 内返回 404 ResponseEntity |
| 注销 Builtin Skill | 400 | Controller 内检查 source 类型后返回 400 |
| 触发禁用工作流 | 400 | Controller 内检查 enabled 状态后返回 400 |
| 文件上传 I/O 异常 | 500 | 复用已有 RuntimeException handler |
| DocumentIngester 异步处理失败 | 不影响上传响应 | 文档状态更新为 ERROR，前端轮询查看 |

### 前端错误处理策略

- 所有 API 调用通过统一 `request()` 函数，非 2xx 响应抛出 `ErrorResponse` 对象
- 各页面 Store 的 action 中 catch 错误，设置 `error` 状态字段
- 页面组件监听 Store 的 `error` 字段，显示 toast 提示
- 网络错误（fetch 失败）统一转换为 `{ code: 0, message: '网络连接失败' }` 格式

## Testing Strategy

### 测试框架

- 后端：JUnit 5 + Mockito（单元测试）+ @SpringBootTest（集成测试）
- 后端属性测试：jqwik（Java property-based testing 库）
- 前端：Vitest（单元测试）+ fast-check（property-based testing 库）

### 单元测试

后端单元测试（Mock 依赖服务）：

| 测试类 | 覆盖范围 |
|--------|---------|
| `KnowledgeBaseControllerTest` | 知识库 CRUD 端点、文件上传、参数校验、404/400 错误 |
| `SkillControllerTest` | Skill 列表/详情/注销、MCP Server 列表/连接/断开/工具查询、Builtin 注销拒绝 |
| `TraceControllerTest` | 轨迹列表分页、详情、步骤查询、404 错误 |
| `WorkflowControllerTest` | 工作流列表/详情/启用/禁用/触发/执行历史、禁用触发拒绝 |
| `TraceQueryServiceTest` | SQL 查询正确性、分页逻辑、排序、空结果处理 |

前端单元测试：

| 测试文件 | 覆盖范围 |
|---------|---------|
| `api/client.test.ts` | API 客户端各函数调用正确性、错误处理 |
| `components/a2ui/A2uiTable.test.ts` | Table 组件渲染、空数据占位 |
| `components/a2ui/A2uiCodeBlock.test.ts` | CodeBlock 渲染、复制功能、无语言降级 |
| `components/a2ui/A2uiProgress.test.ts` | Progress 渲染、值钳制、标签显示 |

### 属性测试

每个属性测试最少运行 100 次迭代，使用 `@Tag` 注解标记对应的设计属性。

后端属性测试（jqwik）：

| 测试 | 对应属性 | 说明 |
|------|---------|------|
| `KnowledgeBaseController_透传一致性` | Property 1 | 生成随机 KnowledgeBase 列表，验证 Controller 返回一致 |
| `KnowledgeBaseController_不支持文件格式被拒绝` | Property 2 | 生成随机非法文件扩展名，验证返回 400 |
| `SkillController_Builtin不可注销` | Property 3 | 生成随机 Builtin Skill，验证注销返回 400 |
| `SkillController_MCP工具查询一致性` | Property 4 | 生成随机工具列表，验证端点返回一致 |
| `TraceQueryService_轨迹列表倒序` | Property 5 | 插入随机时间戳的轨迹，验证返回倒序 |
| `TraceQueryService_步骤升序` | Property 6 | 插入随机步骤，验证返回按 stepIndex 升序 |
| `WorkflowController_禁用工作流不可触发` | Property 7 | 生成随机禁用工作流，验证触发返回 400 |
| `SkillRegistry_listAll完整性` | Property 8 | 注册随机数量 Skill，验证 listAll 返回完整 |
| `SkillRegistry_listAll不可变性` | Property 9 | 调用 listAll 后尝试修改，验证抛出异常 |
| `WorkflowRepository_执行历史倒序` | Property 10 | 插入随机执行实例，验证返回倒序 |
| `TraceQueryService_写入读取一致性` | Property 13 | 通过 TraceRecorder 写入随机轨迹，通过 TraceQueryService 读取验证一致 |

前端属性测试（fast-check）：

| 测试 | 对应属性 | 说明 |
|------|---------|------|
| `Progress_值钳制` | Property 11 | 生成随机数值，验证钳制到 [0, 100] |
| `API客户端_错误处理` | Property 12 | 生成随机非 2xx 状态码，验证抛出错误 |

### 集成测试

| 测试类 | 覆盖范围 |
|--------|---------|
| `WebAutoConfiguration_集成测试` | 验证 web.enabled=true 时所有 Bean 注册、web.enabled=false 时无 Bean |
| `TraceQueryService_集成测试` | 使用内存 SQLite 验证 SQL 查询正确性 |
| `WorkflowRepository_集成测试` | 验证 findInstancesByWorkflowId SQL 查询 |
