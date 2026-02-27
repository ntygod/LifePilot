# Implementation Plan: Web UI 功能页面

## Overview

模块 19 在 Web UI 框架（模块 18）基础上实现四个功能管理页面及后端 REST API。后端先行：先完成跨模块接口扩展（SkillRegistry.listAll、WorkflowRepository.findInstancesByWorkflowId、DynamicToolRegistry.getToolsByServer），再实现 TraceQueryService 和四个 REST Controller，最后扩展 WebAutoConfiguration 和 WebExceptionHandler。前端部分：先扩展类型定义和 API 客户端，再实现 Pinia Store、四个页面视图和三个 A2UI 扩展组件，最后扩展路由和侧边栏导航。

## Tasks

- [x] 1. 跨模块接口扩展
  - [x] 1.1 在 SkillRegistry 中新增 listAll() 方法
    - 返回 `List.copyOf(skills.values())`，包含所有已注册 SkillDefinition
    - 空注册表时返回空列表
    - _Requirements: 7.1, 7.2, 7.3_

  - [ ]* 1.2 编写 SkillRegistry.listAll() 完整性属性测试
    - **Property 8: SkillRegistry.listAll() 完整性**
    - 使用 jqwik 注册随机数量 SkillDefinition，验证 listAll() 返回长度等于注册数量且包含所有定义
    - **Validates: Requirements 7.1**

  - [ ]* 1.3 编写 SkillRegistry.listAll() 不可变性属性测试
    - **Property 9: SkillRegistry.listAll() 不可变性**
    - 使用 jqwik 调用 listAll() 后尝试 add/remove/set 操作，验证抛出 UnsupportedOperationException
    - **Validates: Requirements 7.3**

  - [x] 1.4 在 WorkflowRepository 中新增 findInstancesByWorkflowId() 方法
    - SQL 查询 `workflow_instances WHERE workflow_id = ? ORDER BY created_at DESC`
    - 无执行实例时返回空列表
    - _Requirements: 8.1, 8.2_

  - [ ]* 1.5 编写 WorkflowRepository.findInstancesByWorkflowId() 按时间倒序属性测试
    - **Property 10: WorkflowRepository.findInstancesByWorkflowId() 按时间倒序**
    - 使用 jqwik 插入随机时间戳的执行实例，验证返回列表 createdAt 倒序
    - **Validates: Requirements 8.1**

  - [x] 1.6 在 DynamicToolRegistry 中新增 getToolsByServer() 方法
    - 从 serverToolIndex 查找指定 server 的工具 ID 列表，映射为 `List<ToolContract>`
    - server 不存在时返回空列表
    - _Requirements: 3.5_

- [x] 2. 实现 TraceQueryService 和后端数据模型
  - [x] 2.1 创建后端 record 数据模型
    - 在 `com.lifepilot.interaction.web.model` 包下创建 CreateKbRequest、TriggerWorkflowRequest、PageResult<T>
    - 在 `com.lifepilot.interaction.web.service` 包下创建 TraceRecord、TraceStepRecord
    - PageResult 紧凑构造器中 items 使用 List.copyOf() 防御性拷贝
    - _Requirements: 1.2, 1.10, 4.1, 4.2, 4.3, 4.4, 5.5_

  - [x] 2.2 实现 TraceQueryService
    - 在 `com.lifepilot.interaction.web.service` 包下创建 TraceQueryService
    - 使用 JdbcTemplate 查询 agent_traces 和 agent_trace_steps 表
    - listTraces(page, size)：按 created_at 倒序分页查询
    - getTrace(traceId)：返回 Optional<TraceRecord>
    - getTraceSteps(traceId)：按 step_index 升序查询
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.6_

  - [ ]* 2.3 编写 TraceQueryService 轨迹列表倒序属性测试
    - **Property 5: 轨迹列表按时间倒序排列**
    - 使用 jqwik + 内存 SQLite 插入随机时间戳的轨迹，验证 listTraces 返回 createdAt 倒序
    - **Validates: Requirements 4.2**

  - [ ]* 2.4 编写 TraceQueryService 步骤升序属性测试
    - **Property 6: 轨迹步骤按 stepIndex 升序排列**
    - 使用 jqwik + 内存 SQLite 插入随机步骤，验证 getTraceSteps 返回 stepIndex 严格升序
    - **Validates: Requirements 4.4**

  - [ ]* 2.5 编写 TraceQueryService 写入-读取一致性属性测试
    - **Property 13: TraceQueryService 写入-读取一致性**
    - 使用 jqwik 通过 TraceRecorder 写入随机轨迹，通过 TraceQueryService 读取验证字段一致
    - **Validates: Requirements 4.1, 4.3**

- [x] 3. Checkpoint — 跨模块接口扩展和 TraceQueryService 验证
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. 实现 KnowledgeBaseController
  - [x] 4.1 创建 KnowledgeBaseController
    - 在 `com.lifepilot.interaction.web.controller` 包下创建
    - GET /api/knowledge-bases → listKnowledgeBases()
    - POST /api/knowledge-bases → createKnowledgeBase(CreateKbRequest)
    - GET /api/knowledge-bases/{id} → getKnowledgeBase(id)
    - DELETE /api/knowledge-bases/{id} → deleteKnowledgeBase(id)
    - GET /api/knowledge-bases/{id}/documents → listDocuments(id)
    - POST /api/knowledge-bases/{id}/documents → uploadDocument(id, MultipartFile)
    - DELETE /api/knowledge-bases/{id}/documents/{docId} → removeDocument(docId)
    - 文件上传：校验扩展名（.pdf/.docx/.md/.txt）→ 保存临时文件 → DocumentIngester.ingest() 异步处理 → 返回 202
    - name 为空返回 400，ID 不存在返回 404，文件格式不支持返回 400
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10_

  - [ ]* 4.2 编写 KnowledgeBaseController 透传一致性属性测试
    - **Property 1: KnowledgeBaseController 透传一致性**
    - 使用 jqwik 生成随机 KnowledgeBase 列表，Mock KnowledgeBaseManager，验证 Controller GET 端点返回与 Manager 一致的数据
    - **Validates: Requirements 1.1, 1.3, 1.6**

  - [ ]* 4.3 编写不支持文件格式被拒绝属性测试
    - **Property 2: 不支持的文件格式被拒绝**
    - 使用 jqwik 生成随机非法文件扩展名，验证上传返回 HTTP 400
    - **Validates: Requirements 1.9**

- [x] 5. 实现 SkillController
  - [x] 5.1 创建 SkillController
    - 在 `com.lifepilot.interaction.web.controller` 包下创建
    - Skill 端点：GET /api/skills → listAll()、GET /api/skills/{id} → find(id)、DELETE /api/skills/{id} → unregister(id)
    - MCP 端点：GET /api/mcp/servers → listServers()、GET /api/mcp/servers/{name} → getServer(name)、POST /api/mcp/servers/{name}/connect、POST /api/mcp/servers/{name}/disconnect、GET /api/mcp/servers/{name}/tools → getToolsByServer(name)
    - Skill 不存在返回 404，注销 Builtin Skill 返回 400
    - MCP Server 不存在返回 404
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [ ]* 5.2 编写 Builtin Skill 不可注销属性测试
    - **Property 3: Builtin Skill 不可注销**
    - 使用 jqwik 生成随机 Builtin Skill，验证 DELETE 返回 400 且 SkillRegistry 中 Skill 仍存在
    - **Validates: Requirements 2.5**

  - [ ]* 5.3 编写 MCP Server 工具查询一致性属性测试
    - **Property 4: MCP Server 工具查询一致性**
    - 使用 jqwik 生成随机工具列表，Mock DynamicToolRegistry，验证 GET /api/mcp/servers/{name}/tools 返回与 getToolsByServer 一致
    - **Validates: Requirements 3.5**

- [x] 6. 实现 TraceController 和 WorkflowController
  - [x] 6.1 创建 TraceController
    - 在 `com.lifepilot.interaction.web.controller` 包下创建
    - GET /api/traces → listTraces(page, size)
    - GET /api/traces/{id} → getTrace(id)
    - GET /api/traces/{id}/steps → getTraceSteps(id)
    - 轨迹不存在返回 404
    - _Requirements: 4.2, 4.3, 4.4, 4.5_

  - [x] 6.2 创建 WorkflowController
    - 在 `com.lifepilot.interaction.web.controller` 包下创建
    - GET /api/workflows → listAll()
    - GET /api/workflows/{id} → find(id)
    - POST /api/workflows/{id}/enable → enable(id)
    - POST /api/workflows/{id}/disable → disable(id)
    - POST /api/workflows/{id}/trigger → execute(id, inputs)
    - GET /api/workflows/{id}/executions → findInstancesByWorkflowId(id)
    - 工作流不存在返回 404，触发禁用工作流返回 400
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_

  - [ ]* 6.3 编写禁用工作流不可触发属性测试
    - **Property 7: 禁用工作流不可触发**
    - 使用 jqwik 生成随机禁用工作流，验证 POST trigger 返回 400 且 WorkflowEngine.execute() 未被调用
    - **Validates: Requirements 5.8**

- [x] 7. 扩展 WebExceptionHandler 和 WebAutoConfiguration
  - [x] 7.1 扩展 WebExceptionHandler
    - 新增 KnowledgeBaseNotFoundException → 404 映射
    - 新增 DocumentNotFoundException → 404 映射
    - _Requirements: 1.8_

  - [x] 7.2 扩展 WebAutoConfiguration
    - 注册 TraceQueryService Bean（@ConditionalOnBean(JdbcTemplate.class)）
    - 注册 KnowledgeBaseController Bean（@ConditionalOnBean(KnowledgeBaseManager.class)）
    - 注册 SkillController Bean（@ConditionalOnBean({SkillRegistry.class, McpServerRegistry.class})）
    - 注册 TraceController Bean（@ConditionalOnBean(TraceQueryService.class)）
    - 注册 WorkflowController Bean（@ConditionalOnBean({WorkflowRegistry.class, WorkflowEngine.class})）
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [x] 8. Checkpoint — 后端完整验证
  - Ensure all tests pass, ask the user if questions arise.
  - `mvn compile` 全量编译无错误
  - `mvn test` 全量测试通过

- [x] 9. 扩展前端类型定义和 API 客户端
  - [x] 9.1 扩展 TypeScript 类型定义
    - 在 `types/index.ts` 中新增：KnowledgeBase、CreateKbRequest、KbDocument、SkillSummary、SkillDetail、McpServer、McpTool、TraceItem、TraceDetail、TraceStep、PageResult<T>、WorkflowItem、WorkflowDetail、WorkflowExecution
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5_

  - [x] 9.2 扩展 API 客户端
    - 在 `api/client.ts` 中新增 knowledgeBaseApi、skillApi、mcpApi、traceApi、workflowApi 五组封装函数
    - 文件上传使用 FormData（不设置 Content-Type）
    - 统一错误处理复用已有 request() 函数
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5, 17.6, 17.7_

  - [ ]* 9.3 编写 API 客户端错误处理属性测试
    - **Property 12: API 客户端错误处理**
    - 使用 fast-check 生成随机非 2xx 状态码，Mock fetch 响应，验证 request() 抛出包含错误码和消息的异常
    - **Validates: Requirements 17.6**

- [x] 10. 实现前端 Pinia Store
  - [x] 10.1 创建 knowledgeBaseStore
    - 在 `stores/knowledgeBase.ts` 中管理知识库列表、当前知识库、文档列表状态
    - 提供 fetchList、create、remove、fetchDocuments、uploadDocument、removeDocument actions
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7_

  - [x] 10.2 创建 skillStore
    - 在 `stores/skill.ts` 中管理 Skill 列表、MCP Server 列表状态
    - 提供 fetchSkills、fetchSkillDetail、unregisterSkill、fetchMcpServers、connectServer、disconnectServer、fetchServerTools actions
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9_

  - [x] 10.3 创建 traceStore
    - 在 `stores/trace.ts` 中管理轨迹列表、当前轨迹、步骤列表状态
    - 提供 fetchList（分页）、fetchDetail、fetchSteps actions
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

  - [x] 10.4 创建 workflowStore
    - 在 `stores/workflow.ts` 中管理工作流列表、当前工作流、执行历史状态
    - 提供 fetchList、fetchDetail、enable、disable、trigger、fetchExecutions actions
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_

- [x] 11. 实现知识库管理页面
  - [x] 11.1 创建 KnowledgeBaseView.vue
    - 卡片视图展示知识库列表（名称、文档数量、创建时间）
    - 创建知识库对话框（输入名称、描述）
    - 点击卡片展示文档列表（文件名、大小、状态、分块数）
    - 文档上传（拖拽 + 点击选择文件）
    - 删除知识库/文档确认对话框
    - API 调用失败显示错误提示
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8_

- [x] 12. 实现 Skill / MCP 管理页面
  - [x] 12.1 创建 SkillManageView.vue
    - 两个 Tab：Skill 列表 + MCP Server 列表
    - Skill 卡片视图（名称、描述、来源类型）
    - Skill 详情抽屉（完整 SkillDefinition、工具列表）
    - 非 Builtin Skill 显示注销按钮 + 确认对话框
    - MCP Server 卡片视图（名称、连接状态、工具数量）
    - MCP Server 连接/断开按钮
    - 点击 MCP Server 卡片展示工具列表
    - API 调用失败显示错误提示
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10_

- [x] 13. 实现轨迹回放页面
  - [x] 13.1 创建 TraceReplayView.vue
    - 轨迹列表（用户消息摘要、步骤数、Token 消耗、执行时间、创建时间）
    - 分页加载
    - 点击轨迹展示详情时间线视图
    - 时间线按 stepIndex 顺序展示每一步（阶段转换、动作类型、工具 ID、耗时）
    - 点击展开步骤显示完整信息（工具输入/输出、护栏拦截信息）
    - 轨迹详情顶部汇总信息（总步骤数、总 Token、总耗时、是否成功）
    - 工具输出超过 500 字符截断 + 展开按钮
    - API 调用失败显示错误提示
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8_

- [x] 14. 实现工作流管理页面
  - [x] 14.1 创建 WorkflowManageView.vue
    - 工作流列表（名称、描述、触发器类型、启用状态、最近执行时间）
    - 点击展示工作流详情（完整定义、触发器配置、步骤列表）
    - 启用/禁用开关
    - 手动触发按钮（禁用工作流时按钮禁用）
    - 执行历史 Tab（执行时间、状态、耗时、失败原因）
    - API 调用失败显示错误提示
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7_

- [x] 15. 实现 A2UI 扩展组件
  - [x] 15.1 创建 A2uiTable 组件
    - 接收 columns（{key, label}[]）和 rows（Record<string, unknown>[]）属性
    - 渲染表头和表体
    - rows 为空时显示"暂无数据"占位
    - 注册到 componentCatalog，type 为 "Table"
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

  - [x] 15.2 创建 A2uiCodeBlock 组件
    - 接收 language（可选）和 code 属性
    - 使用 Shiki 语法高亮渲染代码
    - 提供复制按钮（复制到剪贴板）
    - language 未指定时以纯文本渲染
    - 注册到 componentCatalog，type 为 "CodeBlock"
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5_

  - [x] 15.3 创建 A2uiProgress 组件
    - 接收 value（0-100）和 label（可选）属性
    - 渲染进度条（宽度按 value 百分比填充）+ 百分比数值
    - label 存在时在进度条上方显示标签
    - value 超出 0-100 范围时钳制
    - 注册到 componentCatalog，type 为 "Progress"
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6_

  - [ ]* 15.4 编写 Progress 值钳制属性测试
    - **Property 11: Progress 值钳制**
    - 使用 fast-check 生成随机数值，验证渲染时实际值等于 `Math.max(0, Math.min(100, value))`
    - **Validates: Requirements 15.5**

- [x] 16. 扩展前端路由和侧边栏导航
  - [x] 16.1 扩展 Vue Router
    - 新增四个路由：/knowledge-bases、/skills、/traces、/workflows
    - 使用懒加载 `() => import()`
    - _Requirements: 16.1, 16.3_

  - [x] 16.2 扩展 Sidebar 导航
    - 新增四个导航项（知识库、技能、轨迹、工作流）
    - 使用 router-link，高亮当前活跃路由
    - _Requirements: 16.2, 16.4_

- [x] 17. Final checkpoint — 全量验证
  - Ensure all tests pass, ask the user if questions arise.
  - 后端：`mvn compile` 全量编译无错误
  - 后端：`mvn test` 全量测试通过
  - 前端：`npm run build` 构建无错误

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from design document (13 properties)
- Backend tasks (1-8) must complete before frontend tasks (9-16) that depend on API contracts
- 后端包路径：`com.lifepilot.interaction.web`（Controller、Service、Model）
- 前端项目路径：`lifepilot-web/`（工作区根目录）
- 后端属性测试使用 jqwik，前端属性测试使用 fast-check
- 跨模块接口扩展（任务 1）是所有后端 Controller 的前置依赖
- A2UI 扩展组件（任务 15）独立于页面视图，可并行开发
