# Requirements Document

## Introduction

Web UI 功能页面模块（Phase 5 — 模块 19）在 Web UI 框架（模块 18）基础上，实现四个功能管理页面及其对应的后端 REST API，让用户通过浏览器管理知识库、技能/MCP、轨迹和工作流。同时扩展 A2UI 组件目录，新增 Table、CodeBlock、Progress 三个组件。

核心范围：
- 知识库管理页面（前端 + REST API）：知识库 CRUD、文档上传/删除
- Skill / MCP 管理页面（前端 + REST API）：Skill 列表/注册/注销、MCP Server 列表/连接/断开
- 轨迹回放页面（前端 + REST API）：轨迹列表/详情/步骤查看
- 工作流管理页面（前端 + REST API）：工作流列表/详情/启用禁用/手动触发/执行历史
- A2UI 扩展组件：Table、CodeBlock、Progress

后端新增组件：
- 4 个 REST Controller（KnowledgeBaseController、SkillController、TraceController、WorkflowController）
- 1 个新服务 TraceQueryService（从 SQLite 读取轨迹数据，TraceRecorder 仅负责写入）
- WorkflowRepository 扩展（新增 findInstancesByWorkflowId 方法）
- SkillRegistry 扩展（新增 listAll 方法）

参考文档：
- 架构设计：#[[file:docs/architecture/web-ui-pages.md]]
- 特性设计：#[[file:docs/features/web-ui-pages.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 集成检查：#[[file:.kiro/steering/integration-checklist.md]]

## Glossary

- **KnowledgeBaseController**：知识库管理 REST Controller，提供知识库 CRUD 和文档管理 API 端点
- **SkillController**：Skill / MCP 管理 REST Controller，提供 Skill 列表/注册/注销和 MCP Server 列表/连接/断开 API 端点
- **TraceController**：轨迹查询 REST Controller，提供轨迹列表/详情/步骤查询 API 端点
- **WorkflowController**：工作流管理 REST Controller，提供工作流列表/详情/启用禁用/手动触发/执行历史 API 端点
- **TraceQueryService**：轨迹查询服务，从 SQLite 的 agent_traces 和 agent_trace_steps 表读取轨迹数据（TraceRecorder 仅负责写入，无查询 API）
- **KnowledgeBaseManager**：已有的知识库管理服务（模块 8），提供知识库 CRUD 和文档管理
- **DocumentIngester**：已有的文档摄入服务（模块 8），提供异步文档处理（返回 CompletableFuture）
- **SkillRegistry**：已有的 Skill 注册表（模块 10），提供 register/unregister/find/search/listSummaries
- **McpServerRegistry**：已有的 MCP Server 注册表（模块 4），提供 listServers/getServer/connectServer/disconnectServer
- **DynamicToolRegistry**：已有的工具注册表（模块 3），提供 resolve/getAllTools/getToolSnapshot
- **WorkflowEngine**：已有的工作流执行引擎（模块 15），提供 execute/resume/cancel
- **WorkflowRegistry**：已有的工作流注册表（模块 15），提供 find/listAll/listEnabled/enable/disable
- **WorkflowRepository**：已有的工作流持久化仓库（模块 15），提供定义和实例的查询
- **A2UI_Table**：A2UI 扩展组件，数据表格，支持列定义和行数据渲染
- **A2UI_CodeBlock**：A2UI 扩展组件，代码块，支持语法高亮
- **A2UI_Progress**：A2UI 扩展组件，进度条，显示百分比和标签

## Requirements

### Requirement 1: 知识库管理 REST API

**User Story:** As a 用户, I want 通过 REST API 管理知识库和文档, so that 前端页面可以展示知识库列表、创建/删除知识库、上传/删除文档。

#### Acceptance Criteria

1. WHEN 前端发送 GET 请求到 `/api/knowledge-bases`, THE KnowledgeBaseController SHALL 调用 KnowledgeBaseManager.listKnowledgeBases() 并返回知识库列表
2. WHEN 前端发送 POST 请求到 `/api/knowledge-bases`（携带 name、description、embeddingModel）, THE KnowledgeBaseController SHALL 调用 KnowledgeBaseManager.createKnowledgeBase() 并返回创建的知识库对象
3. WHEN 前端发送 GET 请求到 `/api/knowledge-bases/{id}`, THE KnowledgeBaseController SHALL 调用 KnowledgeBaseManager.getKnowledgeBase() 返回知识库详情
4. WHEN 前端发送 DELETE 请求到 `/api/knowledge-bases/{id}`, THE KnowledgeBaseController SHALL 调用 KnowledgeBaseManager.deleteKnowledgeBase() 删除知识库
5. WHEN 前端发送 POST multipart/form-data 请求到 `/api/knowledge-bases/{id}/documents`（携带文件）, THE KnowledgeBaseController SHALL 将文件保存到临时路径并调用 DocumentIngester.ingest() 异步处理
6. WHEN 前端发送 GET 请求到 `/api/knowledge-bases/{id}/documents`, THE KnowledgeBaseController SHALL 调用 KnowledgeBaseManager.listDocuments() 返回文档列表
7. WHEN 前端发送 DELETE 请求到 `/api/knowledge-bases/{id}/documents/{docId}`, THE KnowledgeBaseController SHALL 调用 KnowledgeBaseManager.removeDocument() 删除文档
8. IF 请求的知识库 ID 不存在, THEN THE KnowledgeBaseController SHALL 返回 HTTP 404 状态码和描述性错误消息
9. IF 上传的文件格式不在支持范围内（PDF / Word / Markdown / TXT）, THEN THE KnowledgeBaseController SHALL 返回 HTTP 400 状态码和描述性错误消息
10. IF POST 创建知识库时 name 为空, THEN THE KnowledgeBaseController SHALL 返回 HTTP 400 状态码和描述性错误消息

### Requirement 2: Skill 管理 REST API

**User Story:** As a 用户, I want 通过 REST API 查看和管理已注册的 Skill, so that 前端页面可以展示 Skill 列表、查看详情、注册和注销 Skill。

#### Acceptance Criteria

1. WHEN 前端发送 GET 请求到 `/api/skills`, THE SkillController SHALL 调用 SkillRegistry.listAll() 返回所有已注册 Skill 的摘要列表
2. WHEN 前端发送 GET 请求到 `/api/skills/{id}`, THE SkillController SHALL 调用 SkillRegistry.find() 返回 Skill 详情（包含完整 SkillDefinition）
3. WHEN 前端发送 DELETE 请求到 `/api/skills/{id}`, THE SkillController SHALL 调用 SkillRegistry.unregister() 注销指定 Skill
4. IF 请求的 Skill ID 不存在, THEN THE SkillController SHALL 返回 HTTP 404 状态码和描述性错误消息
5. IF 尝试注销 Builtin 类型的 Skill, THEN THE SkillController SHALL 返回 HTTP 400 状态码并说明内置 Skill 不可注销

### Requirement 3: MCP Server 管理 REST API

**User Story:** As a 用户, I want 通过 REST API 查看和管理 MCP Server, so that 前端页面可以展示 MCP Server 列表、连接状态、提供的工具列表，并控制连接/断开。

#### Acceptance Criteria

1. WHEN 前端发送 GET 请求到 `/api/mcp/servers`, THE SkillController SHALL 调用 McpServerRegistry.listServers() 返回所有 MCP Server 列表（包含连接状态）
2. WHEN 前端发送 GET 请求到 `/api/mcp/servers/{name}`, THE SkillController SHALL 调用 McpServerRegistry.getServer() 返回指定 MCP Server 详情
3. WHEN 前端发送 POST 请求到 `/api/mcp/servers/{name}/connect`, THE SkillController SHALL 调用 McpServerRegistry.connectServer() 连接指定 MCP Server
4. WHEN 前端发送 POST 请求到 `/api/mcp/servers/{name}/disconnect`, THE SkillController SHALL 调用 McpServerRegistry.disconnectServer() 断开指定 MCP Server
5. WHEN 前端发送 GET 请求到 `/api/mcp/servers/{name}/tools`, THE SkillController SHALL 通过 DynamicToolRegistry 查询该 MCP Server 提供的工具列表
6. IF 请求的 MCP Server name 不存在, THEN THE SkillController SHALL 返回 HTTP 404 状态码和描述性错误消息

### Requirement 4: 轨迹查询服务与 REST API

**User Story:** As a 用户, I want 通过 REST API 查询 Agent 执行轨迹, so that 前端页面可以展示轨迹列表、轨迹详情和每一步的工具调用/LLM 交互信息。

#### Acceptance Criteria

1. THE TraceQueryService SHALL 从 SQLite 的 agent_traces 表读取轨迹记录，从 agent_trace_steps 表读取步骤记录
2. WHEN 前端发送 GET 请求到 `/api/traces`（支持 page、size 分页参数）, THE TraceController SHALL 调用 TraceQueryService 返回按 createdAt 倒序排列的轨迹列表
3. WHEN 前端发送 GET 请求到 `/api/traces/{id}`, THE TraceController SHALL 调用 TraceQueryService 返回轨迹详情（包含 TraceRecord 元数据）
4. WHEN 前端发送 GET 请求到 `/api/traces/{id}/steps`, THE TraceController SHALL 调用 TraceQueryService 返回该轨迹的所有步骤列表（按 stepIndex 升序）
5. IF 请求的轨迹 ID 不存在, THEN THE TraceController SHALL 返回 HTTP 404 状态码和描述性错误消息
6. THE TraceQueryService SHALL 使用 JdbcTemplate 查询 SQLite，与 TraceRecorder 共享相同的数据库表结构

### Requirement 5: 工作流管理 REST API

**User Story:** As a 用户, I want 通过 REST API 管理工作流, so that 前端页面可以展示工作流列表、查看详情、启用/禁用工作流、手动触发执行和查看执行历史。

#### Acceptance Criteria

1. WHEN 前端发送 GET 请求到 `/api/workflows`, THE WorkflowController SHALL 调用 WorkflowRegistry.listAll() 返回所有工作流定义列表
2. WHEN 前端发送 GET 请求到 `/api/workflows/{id}`, THE WorkflowController SHALL 调用 WorkflowRegistry.find() 返回工作流详情（包含完整 WorkflowDefinition）
3. WHEN 前端发送 POST 请求到 `/api/workflows/{id}/enable`, THE WorkflowController SHALL 调用 WorkflowRegistry.enable() 启用工作流
4. WHEN 前端发送 POST 请求到 `/api/workflows/{id}/disable`, THE WorkflowController SHALL 调用 WorkflowRegistry.disable() 禁用工作流
5. WHEN 前端发送 POST 请求到 `/api/workflows/{id}/trigger`（可选携带 inputs Map）, THE WorkflowController SHALL 调用 WorkflowEngine.execute() 手动触发工作流执行并返回 WorkflowInstance
6. WHEN 前端发送 GET 请求到 `/api/workflows/{id}/executions`, THE WorkflowController SHALL 调用 WorkflowRepository 查询该工作流的执行历史列表
7. IF 请求的工作流 ID 不存在, THEN THE WorkflowController SHALL 返回 HTTP 404 状态码和描述性错误消息
8. IF 手动触发已禁用的工作流, THEN THE WorkflowController SHALL 返回 HTTP 400 状态码并说明工作流已禁用

### Requirement 6: 后端自动配置扩展

**User Story:** As a 后端开发者, I want 模块 19 的 REST Controller 通过 Spring Boot 自动配置注册, so that 启用 Web 通道时所有管理页面 API 自动就绪。

#### Acceptance Criteria

1. THE WebAutoConfiguration SHALL 在 `lifepilot.gateway.channels.web.enabled=true` 时注册 KnowledgeBaseController、SkillController、TraceController、WorkflowController
2. THE WebAutoConfiguration SHALL 注册 TraceQueryService Bean
3. WHILE `lifepilot.gateway.channels.web.enabled` 为 false, THE WebAutoConfiguration SHALL 不注册模块 19 新增的任何 Bean
4. THE WebAutoConfiguration SHALL 通过 `@ConditionalOnBean` 确保依赖的服务（KnowledgeBaseManager、SkillRegistry、McpServerRegistry、WorkflowEngine、WorkflowRegistry）已注册

### Requirement 7: SkillRegistry 接口扩展

**User Story:** As a 后端开发者, I want SkillRegistry 提供 listAll 方法, so that SkillController 可以获取所有已注册 Skill 的完整定义列表。

#### Acceptance Criteria

1. THE SkillRegistry SHALL 提供 listAll() 方法，返回 List<SkillDefinition> 包含所有已注册的 Skill 定义
2. WHEN SkillRegistry 中无已注册 Skill, THE listAll() 方法 SHALL 返回空列表
3. THE listAll() 方法 SHALL 返回不可变列表（List.copyOf）

### Requirement 8: WorkflowRepository 接口扩展

**User Story:** As a 后端开发者, I want WorkflowRepository 提供按工作流 ID 查询执行实例的方法, so that WorkflowController 可以获取指定工作流的执行历史。

#### Acceptance Criteria

1. THE WorkflowRepository SHALL 提供 findInstancesByWorkflowId(String workflowId) 方法，返回 List<WorkflowInstance> 按 createdAt 倒序排列
2. WHEN 指定工作流无执行实例, THE findInstancesByWorkflowId() 方法 SHALL 返回空列表

### Requirement 9: 知识库管理前端页面

**User Story:** As a 用户, I want 在浏览器中管理知识库和文档, so that 我可以创建知识库、上传文档、查看文档处理状态和删除不需要的知识库或文档。

#### Acceptance Criteria

1. THE KnowledgeBaseView SHALL 以卡片视图展示所有知识库（名称、文档数量、创建时间）
2. THE KnowledgeBaseView SHALL 提供创建知识库对话框（输入名称、描述）
3. WHEN 用户点击知识库卡片, THE KnowledgeBaseView SHALL 展示该知识库的文档列表（文件名、大小、状态、分块数）
4. THE KnowledgeBaseView SHALL 提供文档上传功能，支持拖拽上传和点击选择文件
5. WHEN 用户上传文档, THE KnowledgeBaseView SHALL 调用 POST `/api/knowledge-bases/{id}/documents` 并显示上传进度
6. WHEN 用户点击删除知识库按钮, THE KnowledgeBaseView SHALL 弹出确认对话框，确认后调用 DELETE `/api/knowledge-bases/{id}`
7. WHEN 用户点击删除文档按钮, THE KnowledgeBaseView SHALL 弹出确认对话框，确认后调用 DELETE `/api/knowledge-bases/{id}/documents/{docId}`
8. IF API 调用失败, THEN THE KnowledgeBaseView SHALL 显示错误提示信息

### Requirement 10: Skill / MCP 管理前端页面

**User Story:** As a 用户, I want 在浏览器中查看和管理 Skill 和 MCP Server, so that 我可以了解当前可用的技能和工具，并控制 Skill 注销和 MCP Server 连接/断开。

#### Acceptance Criteria

1. THE SkillManageView SHALL 包含两个 Tab：Skill 列表和 MCP Server 列表
2. THE Skill 列表 Tab SHALL 以卡片视图展示所有已注册 Skill（名称、描述、来源类型：Builtin / UserDefined / AutoGenerated）
3. WHEN 用户点击 Skill 卡片, THE SkillManageView SHALL 展示 Skill 详情抽屉（完整 SkillDefinition 信息、允许的工具列表）
4. WHEN 用户对非 Builtin 类型的 Skill 点击注销按钮, THE SkillManageView SHALL 弹出确认对话框，确认后调用 DELETE `/api/skills/{id}`
5. THE Builtin 类型的 Skill SHALL 不显示注销按钮
6. THE MCP Server 列表 Tab SHALL 以卡片视图展示所有 MCP Server（名称、连接状态、工具数量）
7. WHEN 用户对已断开的 MCP Server 点击连接按钮, THE SkillManageView SHALL 调用 POST `/api/mcp/servers/{name}/connect`
8. WHEN 用户对已连接的 MCP Server 点击断开按钮, THE SkillManageView SHALL 调用 POST `/api/mcp/servers/{name}/disconnect`
9. WHEN 用户点击 MCP Server 卡片, THE SkillManageView SHALL 展示该 Server 提供的工具列表
10. IF API 调用失败, THEN THE SkillManageView SHALL 显示错误提示信息

### Requirement 11: 轨迹回放前端页面

**User Story:** As a 用户, I want 在浏览器中查看 Agent 执行轨迹, so that 我可以回放 Agent 的推理过程、查看每一步的工具调用和 LLM 交互，辅助调试和优化。

#### Acceptance Criteria

1. THE TraceReplayView SHALL 展示轨迹列表（用户消息摘要、步骤数、Token 消耗、执行时间、创建时间）
2. THE TraceReplayView SHALL 支持分页加载轨迹列表
3. WHEN 用户点击轨迹列表项, THE TraceReplayView SHALL 展示轨迹详情时间线视图
4. THE 时间线视图 SHALL 按 stepIndex 顺序展示每一步（阶段转换、动作类型、工具 ID、耗时）
5. WHEN 用户点击展开某一步, THE TraceReplayView SHALL 显示该步骤的完整信息（工具输入参数、工具输出结果、是否被护栏拦截及原因）
6. THE TraceReplayView SHALL 在轨迹详情顶部显示汇总信息（总步骤数、总 Token 消耗、总耗时、是否成功）
7. WHILE 步骤的工具输出内容超过 500 字符, THE TraceReplayView SHALL 截断显示并提供展开查看完整内容的按钮
8. IF API 调用失败, THEN THE TraceReplayView SHALL 显示错误提示信息

### Requirement 12: 工作流管理前端页面

**User Story:** As a 用户, I want 在浏览器中管理工作流, so that 我可以查看工作流定义、启用/禁用工作流、手动触发执行和查看执行历史。

#### Acceptance Criteria

1. THE WorkflowManageView SHALL 展示工作流列表（名称、描述、触发器类型、启用状态、最近执行时间）
2. WHEN 用户点击工作流列表项, THE WorkflowManageView SHALL 展示工作流详情（完整 WorkflowDefinition 信息、触发器配置、步骤列表）
3. THE WorkflowManageView SHALL 提供启用/禁用开关，调用 POST `/api/workflows/{id}/enable` 或 `/api/workflows/{id}/disable`
4. THE WorkflowManageView SHALL 提供手动触发按钮，调用 POST `/api/workflows/{id}/trigger`
5. WHILE 工作流处于禁用状态, THE 手动触发按钮 SHALL 显示为禁用状态
6. WHEN 用户点击执行历史 Tab, THE WorkflowManageView SHALL 调用 GET `/api/workflows/{id}/executions` 展示执行历史列表（执行时间、状态、耗时、失败原因）
7. IF API 调用失败, THEN THE WorkflowManageView SHALL 显示错误提示信息

### Requirement 13: A2UI 扩展组件 — Table

**User Story:** As a 用户, I want Agent 生成的数据表格在浏览器中渲染为结构化表格, so that 我可以清晰地查看结构化数据。

#### Acceptance Criteria

1. THE A2UI_Table 组件 SHALL 接收 columns（列定义数组，每列包含 key 和 label）和 rows（行数据数组，每行为 key-value Map）属性
2. THE A2UI_Table 组件 SHALL 渲染表头（根据 columns 的 label）和表体（根据 rows 的数据）
3. WHEN rows 为空数组, THE A2UI_Table 组件 SHALL 显示"暂无数据"占位提示
4. THE A2UI_Table 组件 SHALL 注册到 componentCatalog，type 为 "Table"

### Requirement 14: A2UI 扩展组件 — CodeBlock

**User Story:** As a 用户, I want Agent 生成的代码块在浏览器中渲染为语法高亮的代码展示, so that 我可以清晰地阅读代码内容。

#### Acceptance Criteria

1. THE A2UI_CodeBlock 组件 SHALL 接收 language（编程语言标识）和 code（代码内容字符串）属性
2. THE A2UI_CodeBlock 组件 SHALL 使用语法高亮库渲染代码内容
3. THE A2UI_CodeBlock 组件 SHALL 提供复制按钮，点击后将代码内容复制到剪贴板
4. WHEN language 属性未指定, THE A2UI_CodeBlock 组件 SHALL 以纯文本模式渲染代码
5. THE A2UI_CodeBlock 组件 SHALL 注册到 componentCatalog，type 为 "CodeBlock"

### Requirement 15: A2UI 扩展组件 — Progress

**User Story:** As a 用户, I want Agent 生成的进度信息在浏览器中渲染为可视化进度条, so that 我可以直观地了解任务完成进度。

#### Acceptance Criteria

1. THE A2UI_Progress 组件 SHALL 接收 value（0-100 的数值，表示百分比）和 label（可选的文本标签）属性
2. THE A2UI_Progress 组件 SHALL 渲染进度条，宽度按 value 百分比填充
3. THE A2UI_Progress 组件 SHALL 在进度条旁显示百分比数值
4. WHEN label 属性存在, THE A2UI_Progress 组件 SHALL 在进度条上方显示标签文本
5. IF value 超出 0-100 范围, THE A2UI_Progress 组件 SHALL 将 value 钳制到 0-100 范围内
6. THE A2UI_Progress 组件 SHALL 注册到 componentCatalog，type 为 "Progress"

### Requirement 16: 前端路由与导航扩展

**User Story:** As a 用户, I want 通过侧边栏导航访问知识库、技能、轨迹和工作流管理页面, so that 我可以在不同功能页面之间便捷切换。

#### Acceptance Criteria

1. THE Vue Router SHALL 新增四个路由：`/knowledge-bases`（知识库管理）、`/skills`（Skill / MCP 管理）、`/traces`（轨迹回放）、`/workflows`（工作流管理）
2. THE AppLayout 侧边栏 SHALL 新增四个导航项，对应四个管理页面
3. WHEN 用户点击侧边栏导航项, THE Vue Router SHALL 切换到对应页面
4. THE 侧边栏 SHALL 高亮当前活跃的导航项

### Requirement 17: 前端 API 客户端封装

**User Story:** As a 前端开发者, I want 统一的 API 客户端封装, so that 各管理页面可以通过类型安全的函数调用后端 REST API。

#### Acceptance Criteria

1. THE API 客户端 SHALL 为知识库管理提供封装函数（listKnowledgeBases、createKnowledgeBase、getKnowledgeBase、deleteKnowledgeBase、uploadDocument、listDocuments、deleteDocument）
2. THE API 客户端 SHALL 为 Skill 管理提供封装函数（listSkills、getSkill、unregisterSkill）
3. THE API 客户端 SHALL 为 MCP 管理提供封装函数（listMcpServers、getMcpServer、connectMcpServer、disconnectMcpServer、listMcpServerTools）
4. THE API 客户端 SHALL 为轨迹查询提供封装函数（listTraces、getTrace、getTraceSteps）
5. THE API 客户端 SHALL 为工作流管理提供封装函数（listWorkflows、getWorkflow、enableWorkflow、disableWorkflow、triggerWorkflow、listWorkflowExecutions）
6. THE API 客户端 SHALL 使用 fetch API 并统一处理错误响应（非 2xx 状态码抛出包含错误消息的异常）
7. THE API 客户端 SHALL 从 `VITE_API_BASE` 环境变量读取后端基地址
