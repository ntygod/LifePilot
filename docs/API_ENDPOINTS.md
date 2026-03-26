# 知微 API 端点清单

> **文档性质**：API 参考文档
> **最后更新**：2026-03
> **数据来源**：后端 Controller 注解映射，以代码为准

## 目录

- [Chat（对话 + SSE）](#chat对话--sse)
- [Sessions（会话管理）](#sessions会话管理)
- [Memories（记忆管理）](#memories记忆管理)
- [Signals / Notifications（A2UI 信号 + 通知）](#signals--notifications)
- [Notifications（通知管理）](#notifications通知管理)
- [Permissions（工具授权）](#permissions工具授权)
- [Agents（多 Agent 管理）](#agents多-agent-管理)
- [Tools（工具管理）](#tools工具管理)
- [Skills（技能管理）](#skills技能管理)
- [MCP Servers（MCP 客户端管理）](#mcp-servers)
- [Knowledge Bases（知识库）](#knowledge-bases知识库)
- [Workflows（工作流）](#workflows工作流)
- [Traces（轨迹）](#traces轨迹)
- [LLM Providers（模型提供商）](#llm-providers模型提供商)
- [Analytics（分析统计）](#analytics分析统计)
- [Marketplace（插件市场）](#marketplace插件市场)
- [Settings（用户设置）](#settings用户设置)
- [Attachments（附件）](#attachments附件)
- [Dependencies（依赖关系）](#dependencies依赖关系)
- [A2A（Agent-to-Agent 协议）](#a2a协议端点)
- [Webhooks（外部渠道回调）](#webhooks外部渠道回调)
- [MCP Server（服务端 JSON-RPC）](#mcp-server服务端-json-rpc)

---

## Chat（对话 + SSE）

来源：`ChatController`，Base Path: `/api/chat`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| POST | `/api/chat/messages` | `sendMessage` | 非流式发送消息 |
| POST | `/api/chat/messages/stream` | `sendMessageStream` | SSE 流式发送（返回 SseEmitter） |
| POST | `/api/chat/messages/upload` | `uploadAttachment` | 上传消息附件（多模态） |
| POST | `/api/chat/messages/{messageId}/feedback` | `submitFeedback` | 提交消息反馈（like/dislike） |

---

## Sessions（会话管理）

来源：`ChatController`，Base Path: `/api/chat`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| POST | `/api/chat/sessions` | `createSession` | 创建会话（201） |
| GET | `/api/chat/sessions` | `listSessions` | 会话列表（支持 q 搜索） |
| GET | `/api/chat/sessions/{id}` | `getSession` | 会话详情（含关联知识库） |
| GET | `/api/chat/sessions/{id}/messages` | `getSessionMessages` | 会话历史消息 |
| PATCH | `/api/chat/sessions/{id}` | `updateSession` | 更新会话（标题/置顶等） |
| PATCH | `/api/chat/sessions/{id}/config` | `updateSessionConfig` | 更新会话配置（knowledgeBaseIds 绑定） |
| DELETE | `/api/chat/sessions/{id}` | `deleteSession` | 删除会话（204） |
| POST | `/api/chat/sessions/batch` | `batchUpdateSessions` | 批量操作（pin/archive/delete） |
| POST | `/api/chat/sessions/{id}/fork` | `forkSession` | 分叉会话（从指定消息复制上下文） |

---

## Memories（记忆管理）

来源：`MemoryController`，Base Path: `/api/memories`

> 所有端点在记忆系统未启用时返回 503 Service Unavailable。

### 统计与搜索

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/memories/stats` | `getStats` | 记忆统计概览（L2/L3/L4 各层计数） |
| GET | `/api/memories/search` | `search` | 统一记忆搜索（q/topK 参数，调用 HybridRetriever） |

### L3 语义记忆（实体 + 关系）

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/memories/entities` | `listEntities` | 实体分页列表（type/q/timeFrom/timeTo/sortBy/order 过滤排序） |
| GET | `/api/memories/entities/{id}` | `getEntity` | 实体详情 |
| GET | `/api/memories/entities/{id}/history` | `getEntityHistory` | 实体版本历史 |
| GET | `/api/memories/entities/{id}/related` | `getRelatedEntities` | 关联实体列表 |
| DELETE | `/api/memories/entities/{id}` | `archiveEntity` | 归档实体（软删除，204） |
| GET | `/api/memories/relations` | `listRelations` | 关系分页列表（entityId/relationType 过滤，附带实体名称） |

### L2 情景记忆（对话）

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/memories/conversations` | `listConversations` | 对话分页列表（q/timeFrom/timeTo 过滤） |
| GET | `/api/memories/conversations/{id}` | `getConversation` | 对话详情（含消息列表） |
| DELETE | `/api/memories/conversations/{id}` | `deleteConversation` | 删除对话（204） |

### L4 程序记忆（模板 + 偏好）

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/memories/templates` | `listTemplates` | 响应模板列表（category 过滤） |
| GET | `/api/memories/templates/{id}` | `getTemplate` | 模板详情 |
| DELETE | `/api/memories/templates/{id}` | `deleteTemplate` | 删除模板（204） |
| GET | `/api/memories/preferences` | `listPreferences` | 用户偏好列表（category 过滤） |
| DELETE | `/api/memories/preferences/{id}` | `deletePreference` | 删除偏好（204） |

### 遗忘日志 + 巩固

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/memories/forgetting-logs` | `listForgettingLogs` | 遗忘日志分页列表（timeFrom/timeTo/strategy 过滤） |
| POST | `/api/memories/consolidate` | `triggerConsolidation` | 手动触发记忆巩固（AtomicBoolean 防重入，Virtual Thread 异步，202） |

---

## Signals / Notifications

来源：`ChatController`，Base Path: `/api/chat`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| POST | `/api/chat/signals` | `handleSignal` | A2UI 信号回传 |
| GET | `/api/notifications/stream` | `notificationStream` | 通知 SSE 流，建立后先推送未读数快照 |

---

## Notifications（通知管理）

来源：`NotificationController`，Base Path: `/api/notifications`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/notifications` | `listNotifications` | 通知历史分页查询（page/size，按 sentAt 降序） |
| PUT | `/api/notifications/{id}/read` | `markAsRead` | 标记单条通知已读（404 if 不存在） |
| PUT | `/api/notifications/read-all` | `markAllAsRead` | 批量标记所有通知已读，返回更新数量 |

---

## Permissions（工具授权）

来源：`PermissionController`，Base Path: `/api/permissions`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/permissions/grants` | `listGrants` | 查询授权记录（支持 `activeOnly/subjectType/subjectId` 过滤） |
| POST | `/api/permissions/grants` | `createGrant` | 手动创建授权 |
| POST | `/api/permissions/approvals/{requestId}` | `resolveApproval` | 回传聊天内授权结果 |
| DELETE | `/api/permissions/grants/{grantId}` | `revokeGrant` | 撤销授权（支持 `revokedBy/reason`） |

---

## Agents（多 Agent 管理）

来源：`AgentController`，Base Path: `/api/agents`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/agents` | `listAgents` | Agent 列表（q/type 过滤） |
| POST | `/api/agents` | `createAgent` | 创建 Agent（201） |
| GET | `/api/agents/{id}` | `getAgent` | Agent 详情 |
| PUT | `/api/agents/{id}` | `updateAgent` | 更新 Agent |
| DELETE | `/api/agents/{id}` | `deleteAgent` | 删除 Agent（204） |
| POST | `/api/agents/{id}/enable` | `enableAgent` | 启用 Agent（204） |
| POST | `/api/agents/{id}/disable` | `disableAgent` | 禁用 Agent（204） |
| POST | `/api/agents/{id}/test-chat` | `testChat` | 测试对话 |
| POST | `/api/agents/{id}/context-preview` | `contextPreview` | 上下文预览 |

---

## Tools（工具管理）

来源：`ToolController`，Base Path: `/api`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/tools` | `listTools` | Tool 列表（source/category 过滤） |
| POST | `/api/tools` | `createTool` | 创建 Tool（201） |
| GET | `/api/tools/{id}` | `getTool` | Tool 详情 |
| PUT | `/api/tools/{id}` | `updateTool` | 更新 Tool |
| DELETE | `/api/tools/{id}` | `deleteTool` | 删除 Tool |
| POST | `/api/tools/{id}/test` | `testTool` | 测试 Tool 执行 |
| GET | `/api/tools/{id}/usage` | `getToolUsage` | Tool 使用统计 |

---

## Skills（技能管理）

来源：`SkillController`，Base Path: `/api`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/skills` | `listSkills` | Skill 列表（name/sourceType/toolName 过滤） |
| GET | `/api/skills/{id}` | `getSkill` | Skill 详情 |
| DELETE | `/api/skills/{id}` | `unregisterSkill` | 注销 Skill（Builtin 不可注销） |
| POST | `/api/skills/{id}/enable` | `enableSkill` | 启用（204） |
| POST | `/api/skills/{id}/disable` | `disableSkill` | 禁用（204） |
| POST | `/api/skills/{id}/test` | `testSkill` | Skill 测试 |

---

## MCP Servers

来源：`SkillController`，Base Path: `/api`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/mcp/servers` | `listMcpServers` | MCP Server 列表 |
| GET | `/api/mcp/servers/{name}` | `getMcpServer` | Server 详情 |
| POST | `/api/mcp/servers/{name}/connect` | `connectMcpServer` | 连接（202） |
| POST | `/api/mcp/servers/{name}/disconnect` | `disconnectMcpServer` | 断开（204） |
| GET | `/api/mcp/servers/{name}/tools` | `getMcpServerTools` | 工具列表 |
| GET | `/api/mcp/servers/{name}/last-errors` | `getMcpServerLastErrors` | 最近错误摘要 |

---

## Knowledge Bases（知识库）

来源：`KnowledgeBaseController`，Base Path: `/api/knowledge-bases`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/knowledge-bases` | `listKnowledgeBases` | 知识库列表（q/tags 过滤） |
| POST | `/api/knowledge-bases` | `createKnowledgeBase` | 创建（201） |
| GET | `/api/knowledge-bases/{id}` | `getKnowledgeBase` | 详情 |
| PATCH | `/api/knowledge-bases/{id}` | `updateKnowledgeBase` | 更新配置 |
| DELETE | `/api/knowledge-bases/{id}` | `deleteKnowledgeBase` | 删除（204） |
| GET | `/api/knowledge-bases/{id}/documents` | `listDocuments` | 文档列表 |
| POST | `/api/knowledge-bases/{id}/documents` | `uploadDocument` | 上传文档（multipart） |
| DELETE | `/api/knowledge-bases/{id}/documents/{docId}` | `removeDocument` | 删除文档（204） |
| GET | `/api/knowledge-bases/{id}/documents/{docId}/logs` | `getDocumentLogs` | 文档处理日志 |
| POST | `/api/knowledge-bases/{id}/documents/{docId}/retry` | `retryDocument` | 重试导入（202） |
| POST | `/api/knowledge-bases/{id}/documents/{docId}/rechunk` | `rechunkDocument` | 重新分块（202） |
| GET | `/api/knowledge-bases/{id}/stats` | `getStats` | 统计信息 |
| POST | `/api/knowledge-bases/{id}/test-retrieval` | `testRetrieval` | 测试检索 |

---

## Workflows（工作流）

来源：`WorkflowController`，Base Path: `/api/workflows`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/workflows` | `listWorkflows` | 工作流列表 |
| POST | `/api/workflows` | `createWorkflow` | 创建工作流 |
| GET | `/api/workflows/{id}` | `getWorkflow` | 工作流定义 |
| PUT | `/api/workflows/{id}` | `updateWorkflow` | 更新工作流 |
| DELETE | `/api/workflows/{id}` | `deleteWorkflow` | 删除（204） |
| GET | `/api/workflows/{id}/yaml` | `getWorkflowYaml` | 获取 YAML 源文件 |
| POST | `/api/workflows/{id}/enable` | `enableWorkflow` | 启用（204） |
| POST | `/api/workflows/{id}/disable` | `disableWorkflow` | 禁用（204） |
| POST | `/api/workflows/{id}/trigger` | `triggerWorkflow` | 手动触发执行 |
| GET | `/api/workflows/{id}/executions` | `getWorkflowExecutions` | 执行历史 |
| GET | `/api/workflows/executions/{instanceId}` | `getInstance` | 实例详情 |
| GET | `/api/workflows/executions/{instanceId}/events` | `getEventTimeline` | 事件时间线 |
| POST | `/api/workflows/executions/{instanceId}/steps/{stepId}/approve` | `approveStep` | 审批步骤 |

---

## Traces（轨迹）

来源：`TraceController`，Base Path: `/api/traces`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/traces` | `listTraces` | 轨迹列表（page/size/q/status/modelId/时间范围/Token 范围） |
| GET | `/api/traces/{id}` | `getTrace` | 轨迹详情（含步骤） |
| GET | `/api/traces/{id}/steps` | `getTraceSteps` | 回放步骤列表 |
| GET | `/api/traces/{traceId}/summary` | `getTraceSummary` | 轨迹摘要（不含步骤） |
| GET | `/api/traces/{id}/export` | `exportTrace` | 导出（format=json/md） |
| GET | `/api/traces/stats/overview` | `getOverviewStats` | 总览统计（window=24h/7d/30d） |
| GET | `/api/traces/stats/tools` | `getToolStats` | 工具调用统计 |

---

## LLM Providers（模型提供商）

来源：`LlmProviderController`，Base Path: `/api/llm-providers`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/llm-providers/enabled` | `listEnabledProviders` | 已启用 Provider 列表 |
| GET | `/api/llm-providers/presets` | `listPresets` | 预设置 Provider 列表 |
| GET | `/api/llm-providers/{id}` | `getProvider` | Provider 详情 |
| PUT | `/api/llm-providers/{id}` | `updateProvider` | 更新 Provider |
| DELETE | `/api/llm-providers/{id}` | `deleteProvider` | 删除（预设置不可删除） |
| GET | `/api/llm-providers/{id}/health` | `getProviderHealth` | 健康状态 |

---

## Analytics（分析统计）

来源：`AnalyticsController`，Base Path: `/api/analytics`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/analytics/usage` | `getUsageStats` | 用量统计（from/to 必填） |
| GET | `/api/analytics/agents` | `getAgentStats` | Agent 统计 |
| GET | `/api/analytics/knowledge-bases` | `getKnowledgeBaseStats` | 知识库统计 |
| GET | `/api/analytics/tools` | `getToolAnalytics` | Tool 调用统计 |
| GET | `/api/analytics/error-trend` | `getErrorTrend` | 每日错误趋势 |

---

## Marketplace（插件市场）

来源：`MarketplaceController`，Base Path: `/api/marketplace`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/marketplace/extensions` | `listExtensions` | 扩展列表（type/keyword/page/size） |
| GET | `/api/marketplace/extensions/{id}` | `getExtension` | 扩展详情 |
| POST | `/api/marketplace/extensions/{id}/install` | `installExtension` | 安装扩展 |
| DELETE | `/api/marketplace/extensions/{id}` | `uninstallExtension` | 卸载扩展 |
| POST | `/api/marketplace/extensions/{id}/upgrade` | `upgradeExtension` | 升级扩展 |
| POST | `/api/marketplace/index/refresh` | `refreshIndex` | 刷新索引 |
| GET | `/api/marketplace/updates` | `getUpdates` | 查询可用更新 |

---

## Settings（用户设置）

来源：`SettingsController`，Base Path: `/api/settings`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/settings` | `getSettings` | 获取设置 |
| PUT | `/api/settings` | `updateSettings` | 更新设置 |

---

## Attachments（附件）

来源：`AttachmentController`，Base Path: `/api/attachments`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/attachments/{id}` | `getAttachment` | 获取附件（返回文件流） |

---

## Dependencies（依赖关系）

来源：`DependencyController`，Base Path: `/api/dependencies`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/dependencies/graph` | `getGraph` | 模块依赖关系图 |

---

## A2A（协议端点）

来源：`com.lifepilot.a2a.server.*`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/.well-known/agent.json` | `AgentCardController#getAgentCard` | Agent Card（标准发现路径） |
| GET | `/api/a2a/agent-card` | `AgentCardController#getAgentCardAlternate` | Agent Card（备用路径） |
| POST | `/api/a2a/message/send` | `A2aMessageController#sendMessage` | 同步消息处理 |
| POST | `/api/a2a/message/stream` | `A2aMessageController#streamMessage` | SSE 流式消息 |
| GET | `/api/a2a/tasks/{id}` | `A2aTaskController#getTask` | 查询 Task |
| POST | `/api/a2a/tasks/{id}/cancel` | `A2aTaskController#cancelTask` | 取消 Task |

---

## Webhooks（外部渠道回调）

来源：`WebhookController`，Base Path: `/api/webhook`

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| GET | `/api/webhook/wecom` | `wecomVerify` | 企微 URL 验证 |
| POST | `/api/webhook/wecom` | `wecomMessage` | 企微消息接收 |
| POST | `/api/webhook/dingtalk` | `dingtalkMessage` | 钉钉消息接收 |
| POST | `/api/webhook/feishu` | `feishuEvent` | 飞书事件接收 |

---

## MCP Server（服务端 JSON-RPC）

来源：`McpServerEndpoint`，Base Path: `/mcp`

> 该端点不在 `/api` 前缀下，仅在 `lifepilot.mcp.server.enabled=true` 时启用。

| Method | Path | Handler | 备注 |
|--------|------|---------|------|
| POST | `/mcp` | `handleRequest` | MCP Streamable HTTP（JSON-RPC）：initialize / tools/list / tools/call |
