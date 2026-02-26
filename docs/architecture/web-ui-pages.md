# Web UI 功能页面架构设计

> **模块编号**：Phase 5 — 模块 19
> **依赖模块**：Web UI 框架（模块 18）、知识库管理（模块 8）、Skill 系统（模块 10）、MCP 协议（模块 4）、Gateway（模块 13）、工作流编排（模块 15）、可观测性（模块 20）
> **最后更新**：2026-02-27

---

## 1. 模块定位与职责边界

模块 19 在模块 18 搭建的 Web UI 框架基础上，实现四个功能管理页面及其对应的后端 REST API。每个页面对接已有后端模块的服务层，通过新增 REST Controller 暴露 CRUD 操作。

### 职责边界

| 属于本模块 | 不属于本模块 |
|-----------|------------|
| 知识库管理页面（前端 + REST API） | 知识库核心逻辑（模块 8 已实现） |
| Skill / MCP 管理页面（前端 + REST API） | Skill 注册/激活逻辑（模块 10 已实现） |
| 轨迹回放页面（前端 + REST API） | TraceRecorder 核心逻辑（模块 2 已实现） |
| 工作流管理页面（前端 + REST API） | WorkflowEngine 核心逻辑（模块 15 已实现） |
| A2UI 扩展组件（Table / CodeBlock / Progress） | A2UI 渲染器核心（模块 18 已实现） |
| 前端路由扩展、侧边栏导航更新 | 对话页 / 设置页（模块 18 已实现） |

---

## 2. 核心概念与术语

| 术语 | 定义 |
|------|------|
| 知识库（Knowledge Base） | 文档集合，经过分块和向量化后支持语义检索，由模块 8 DocumentIngester 管理 |
| Skill | YAML 声明式技能定义，由模块 10 SkillRegistry 管理，支持热加载 |
| MCP Server | Model Context Protocol 服务器，提供外部工具能力，由模块 4 管理 |
| Trace | Agent 执行轨迹记录，包含每一步的动作、工具调用、LLM 交互，由 TraceRecorder 记录 |
| Workflow | YAML 声明式自动化工作流，由模块 15 WorkflowEngine 执行 |

---

## 3. 架构设计

### 3.1 整体架构

```
前端 (lifepilot-web)
├── KnowledgeBaseView.vue    ← REST API → KnowledgeBaseController
├── SkillManageView.vue      ← REST API → SkillController
├── TraceReplayView.vue      ← REST API → TraceController
└── WorkflowManageView.vue   ← REST API → WorkflowController

后端新增 Controller（com.lifepilot.interaction.web.controller）
├── KnowledgeBaseController  → 调用 KnowledgeBaseManager（模块 8）
├── SkillController          → 调用 SkillRegistry（模块 10）+ McpServerManager（模块 4）
├── TraceController          → 调用 TraceRecorder / TraceStore（模块 2）
└── WorkflowController       → 调用 WorkflowEngine / WorkflowStore（模块 15）
```

### 3.2 后端 REST API 设计

#### 3.2.1 知识库管理 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/knowledge-bases` | 列出所有知识库 |
| POST | `/api/knowledge-bases` | 创建知识库 |
| GET | `/api/knowledge-bases/{id}` | 获取知识库详情（含文档列表） |
| DELETE | `/api/knowledge-bases/{id}` | 删除知识库 |
| POST | `/api/knowledge-bases/{id}/documents` | 上传文档到知识库 |
| DELETE | `/api/knowledge-bases/{id}/documents/{docId}` | 删除文档 |

#### 3.2.2 Skill / MCP 管理 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/skills` | 列出所有已注册 Skill |
| GET | `/api/skills/{id}` | 获取 Skill 详情（YAML 定义 + 状态） |
| POST | `/api/skills/{id}/toggle` | 启用/禁用 Skill |
| GET | `/api/mcp/servers` | 列出所有 MCP Server |
| GET | `/api/mcp/servers/{id}/tools` | 获取 MCP Server 提供的工具列表 |
| POST | `/api/mcp/servers/{id}/toggle` | 启用/禁用 MCP Server |

#### 3.2.3 轨迹查询 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/traces` | 列出轨迹（分页，按时间倒序） |
| GET | `/api/traces/{id}` | 获取轨迹详情（完整步骤列表） |
| GET | `/api/traces/{id}/steps` | 获取轨迹步骤（含工具调用、LLM 交互） |

#### 3.2.4 工作流管理 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/workflows` | 列出所有工作流定义 |
| GET | `/api/workflows/{id}` | 获取工作流详情（YAML 定义 + 执行历史） |
| POST | `/api/workflows/{id}/toggle` | 启用/禁用工作流 |
| POST | `/api/workflows/{id}/trigger` | 手动触发工作流执行 |
| GET | `/api/workflows/{id}/executions` | 获取工作流执行历史 |

### 3.3 前端页面设计

#### 3.3.1 知识库管理页

- 知识库列表卡片视图（名称、文档数、创建时间）
- 创建知识库对话框（名称、描述、分块策略选择）
- 知识库详情：文档列表 + 上传按钮 + 删除操作
- 文档上传支持拖拽，显示处理进度

#### 3.3.2 Skill / MCP 管理页

- 两个 Tab：Skill 列表 / MCP Server 列表
- Skill 卡片：名称、描述、来源（builtin / yaml / auto-generated）、启用开关
- Skill 详情抽屉：YAML 定义只读展示 + 工具列表
- MCP Server 卡片：名称、状态（connected / disconnected）、工具数量、启用开关

#### 3.3.3 轨迹回放页

- 轨迹列表（时间、会话摘要、步骤数、Token 消耗）
- 轨迹详情：时间线视图，每步展示动作类型、工具调用参数/结果、LLM 输入/输出
- 步骤折叠/展开，长文本截断 + 展开查看
- 参考 Langfuse 的 trace 可视化设计：层级嵌套、耗时标注

#### 3.3.4 工作流管理页

- 工作流列表（名称、触发器类型、状态、最近执行时间）
- 工作流详情：YAML 定义只读展示 + 触发器配置 + 执行历史
- 手动触发按钮
- 执行历史列表（时间、状态、耗时）

### 3.4 A2UI 扩展组件

模块 19 扩展 componentCatalog，新增以下组件类型：

| A2UI Type | Vue 组件 | 用途 |
|-----------|---------|------|
| `Table` | `A2uiTable.vue` | 数据表格（列定义 + 行数据） |
| `CodeBlock` | `A2uiCodeBlock.vue` | 代码块（语言 + 代码内容 + 高亮） |
| `Progress` | `A2uiProgress.vue` | 进度条（百分比 + 标签） |

---

## 4. 关键设计决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | 管理页面为只读 + 开关操作，不支持在线编辑 YAML | MVP 阶段优先展示和控制，YAML 编辑通过文件系统完成 |
| 2 | 轨迹回放采用时间线视图而非树形视图 | 时间线更直观，与 Langfuse 等工具的 UX 一致 |
| 3 | 后端 Controller 薄层封装，直接调用已有服务 | 避免重复业务逻辑，Controller 只做 HTTP 协议转换 |
| 4 | 文档上传使用 multipart/form-data | 标准文件上传方式，前端 FormData API 原生支持 |
| 5 | 前端路由扩展现有 router，不引入新的路由库 | 保持与模块 18 一致的技术栈 |

---

## 5. 与已有模块的集成点

### 5.1 知识库模块（模块 8）

- `KnowledgeBaseManager`：知识库 CRUD 操作
- `DocumentIngester`：文档上传和处理
- `KnowledgeBaseStore`：知识库元数据持久化

### 5.2 Skill 系统（模块 10）

- `SkillRegistry`：Skill 注册表查询
- `SkillActivator`：Skill 启用/禁用

### 5.3 MCP 协议（模块 4）

- `McpServerManager`：MCP Server 管理
- `DynamicToolRegistry`：工具注册表查询

### 5.4 Agent 引擎（模块 2）

- `TraceRecorder`：轨迹记录
- `TraceStore`：轨迹持久化和查询

### 5.5 工作流编排（模块 15）

- `WorkflowEngine`：工作流执行
- `WorkflowStore`：工作流定义和执行历史持久化

---

## 6. 调研参考

| 来源 | 借鉴点 |
|------|--------|
| [Langfuse](https://langfuse.com) | 轨迹可视化 UI 设计：层级嵌套时间线、耗时标注、Token 统计 |
| [Open WebUI RAG](https://docs.openwebui.com) | 知识库管理 UI：文档列表、上传流程、分块策略配置 |
| [LobeChat MCP 市场](https://lobehub.com) | MCP/插件管理 UI：卡片视图、一键启用/禁用 |
| [OpenAI AgentKit](https://openai.com/index/introducing-agentkit/) | 工作流可视化编辑器设计理念（本模块 MVP 不含可视化编辑，但参考其 UX） |

> 内容已重新组织表述以符合许可要求。
