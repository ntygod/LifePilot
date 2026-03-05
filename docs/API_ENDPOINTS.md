# 项目 API 端点清单（后端）

本清单基于后端 `Controller` 注解映射整理（截至 2026-02-28）。如需更新，以代码为准。

## 目录

- [Chat（对话 + SSE）](#chat对话--sse)
- [Sessions（会话管理）](#sessions会话管理)
- [Signals（A2UI 信号）](#signalsa2ui-信号)
- [Workflows（工作流）](#workflows工作流)
- [Traces（轨迹）](#traces轨迹)
- [Knowledge Bases（知识库）](#knowledge-bases知识库)
- [Skills（技能）](#skills技能)
- [MCP Servers（客户端侧：连接/工具列表）](#mcp-servers客户端侧连接工具列表)
- [Settings（用户设置）](#settings用户设置)
- [A2A（协议端点）](#a2a协议端点)
- [Webhooks（外部渠道回调）](#webhooks外部渠道回调)
- [MCP Server（服务端 JSON-RPC）](#mcp-server服务端-json-rpc)

---

## Chat（对话 + SSE）

来源：`com.lifepilot.interaction.web.controller.ChatController`，Base Path: `/api/chat`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| POST | `/api/chat/messages` | `sendMessage` | 非流式发送消息 |
| POST | `/api/chat/messages/stream` | `sendMessageStream` | SSE 流式发送消息（返回 `SseEmitter`） |
| POST | `/api/chat/messages/upload` | `uploadAttachment` | 上传消息附件（返回 `attachmentId/url`，用于多模态） |

---

## Sessions（会话管理）

来源：`ChatController`，Base Path: `/api/chat`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| POST | `/api/chat/sessions` | `createSession` | 创建会话（201） |
| GET | `/api/chat/sessions` | `listSessions` | 会话列表 |
| GET | `/api/chat/sessions/{id}` | `getSession` | 获取会话详情（含关联知识库） |
| GET | `/api/chat/sessions/{id}/messages` | `getSessionMessages` | 会话历史消息 |
| PATCH | `/api/chat/sessions/{id}` | `updateSession` | 更新会话（标题/置顶等） |
| PATCH | `/api/chat/sessions/{id}/config` | `updateSessionConfig` | 更新会话配置（含 `knowledgeBaseIds` 绑定） |
| DELETE | `/api/chat/sessions/{id}` | `deleteSession` | 删除会话（204） |
| POST | `/api/chat/sessions/{id}/clear` | `clearSessionMessages` | 清空会话消息（204） |
| POST | `/api/chat/sessions/batch` | `batchUpdateSessions` | 批量操作会话（pin/archive/delete 等） |
| POST | `/api/chat/sessions/{id}/fork` | `forkSession` | 分叉会话（从指定消息复制上下文） |

---

## Signals（A2UI 信号）

来源：`ChatController`，Base Path: `/api/chat`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| POST | `/api/chat/signals` | `handleSignal` | A2UI 信号回传 |

---

## Workflows（工作流）

来源：`com.lifepilot.interaction.web.controller.WorkflowController`，Base Path: `/api/workflows`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| GET | `/api/workflows` | `listWorkflows` | 支持 name/triggerType/enabled/sortBy/order |
| GET | `/api/workflows/{id}` | `getWorkflow` | 获取工作流定义 |
| POST | `/api/workflows/{id}/enable` | `enableWorkflow` | 启用（204） |
| POST | `/api/workflows/{id}/disable` | `disableWorkflow` | 禁用（204） |
| POST | `/api/workflows/{id}/trigger` | `triggerWorkflow` | 手动触发执行 |
| GET | `/api/workflows/{id}/executions` | `getWorkflowExecutions` | 执行历史（page/size） |
| GET | `/api/workflows/{id}/executions/{instanceId}` | `getWorkflowExecutionDetail` | 实例详情 + stepLogs |
| POST | `/api/workflows/{id}/executions/{instanceId}/retry` | `retryWorkflowExecution` | 仅 FAILED 可重试 |
| POST | `/api/workflows/{id}/executions/{instanceId}/cancel` | `cancelWorkflowExecution` | 仅 RUNNING/WAITING 可取消 |
| GET | `/api/workflows/{id}/stats` | `getWorkflowStats` | 统计信息 |
| GET | `/api/workflows/{id}/triggers` | `getWorkflowTriggers` | 触发器状态 |

---

## Traces（轨迹）

来源：`com.lifepilot.interaction.web.controller.TraceController`，Base Path: `/api/traces`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| GET | `/api/traces` | `listTraces` | page/size/q/status/modelId/timeFrom/timeTo/minTokens/maxTokens/sortBy/order |
| GET | `/api/traces/{id}` | `getTrace` | 轨迹详情（含步骤） |
| GET | `/api/traces/{id}/steps` | `getTraceSteps` | 回放步骤列表 |
| GET | `/api/traces/{traceId}/summary` | `getTraceSummary` | 轨迹摘要（不含步骤） |
| GET | `/api/traces/{id}/export` | `exportTrace` | `format=json|md` |
| GET | `/api/traces/stats/overview` | `getOverviewStats` | window=24h/7d/30d |
| GET | `/api/traces/stats/tools` | `getToolStats` | 工具调用统计 |

---

## Knowledge Bases（知识库）

来源：`com.lifepilot.interaction.web.controller.KnowledgeBaseController`，Base Path: `/api/knowledge-bases`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| GET | `/api/knowledge-bases` | `listKnowledgeBases` | 知识库列表 |
| POST | `/api/knowledge-bases` | `createKnowledgeBase` | 创建（201） |
| GET | `/api/knowledge-bases/{id}` | `getKnowledgeBase` | 详情 |
| PATCH | `/api/knowledge-bases/{id}` | `updateKnowledgeBaseConfig` | 更新配置 |
| DELETE | `/api/knowledge-bases/{id}` | `deleteKnowledgeBase` | 删除（204） |
| GET | `/api/knowledge-bases/{id}/documents` | `listDocuments` | 文档列表 |
| POST | `/api/knowledge-bases/{id}/documents` | `uploadDocument` | 上传（multipart；通常 202） |
| PATCH | `/api/knowledge-bases/{id}/documents/{docId}` | `updateDocument` | 更新文档信息（displayName/tags） |
| DELETE | `/api/knowledge-bases/{id}/documents/{docId}` | `removeDocument` | 删除文档（204） |
| GET | `/api/knowledge-bases/{id}/documents/{docId}/download` | `downloadDocument` | 下载/预览原文件 |
| POST | `/api/knowledge-bases/{id}/documents/{docId}/retry` | `retryDocument` | 重试导入（202） |
| GET | `/api/knowledge-bases/{id}/documents/{docId}/chunks` | `getDocumentChunks` | offset/limit（limit<=100） |
| POST | `/api/knowledge-bases/{id}/documents/{docId}/rechunk` | `rechunkDocument` | 重新分块（202） |
| GET | `/api/knowledge-bases/{id}/stats` | `getKnowledgeBaseStats` | 统计信息 |

---

## Skills（技能）

来源：`com.lifepilot.interaction.web.controller.SkillController`，Base Path: `/api`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| GET | `/api/skills` | `listSkills` | name/sourceType/toolName |
| GET | `/api/skills/{id}` | `getSkill` | 详情 |
| DELETE | `/api/skills/{id}` | `unregisterSkill` | 注销（Builtin 不可注销） |
| POST | `/api/skills/{id}/enable` | `enableSkill` | 启用（204） |
| POST | `/api/skills/{id}/disable` | `disableSkill` | 禁用（204） |
| POST | `/api/skills/{id}/test` | `testSkill` | Skill 测试（body: userMessage/context） |

---

## MCP Servers（客户端侧：连接/工具列表）

来源：`SkillController`，Base Path: `/api`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| GET | `/api/mcp/servers` | `listMcpServers` | MCP Server 列表 |
| GET | `/api/mcp/servers/{name}` | `getMcpServer` | Server 详情 |
| POST | `/api/mcp/servers/{name}/connect` | `connectMcpServer` | 连接（202） |
| POST | `/api/mcp/servers/{name}/disconnect` | `disconnectMcpServer` | 断开（204） |
| GET | `/api/mcp/servers/{name}/tools` | `getMcpServerTools` | 工具列表 |
| GET | `/api/mcp/servers/{name}/last-errors` | `getMcpServerLastErrors` | 最近错误摘要 |

---

## Settings（用户设置）

来源：`com.lifepilot.interaction.web.controller.SettingsController`，Base Path: `/api/settings`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| GET | `/api/settings` | `getSettings` | 获取设置 |
| PUT | `/api/settings` | `updateSettings` | 更新设置（参数无效抛 `IllegalArgumentException`） |

---

## A2A（协议端点）

来源：`com.lifepilot.a2a.server.*`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| GET | `/.well-known/agent.json` | `AgentCardController#getAgentCard` | Agent Card（标准发现路径，无需认证） |
| GET | `/api/a2a/agent-card` | `AgentCardController#getAgentCardAlternate` | Agent Card（备用路径） |
| POST | `/api/a2a/message/send` | `A2aMessageController#sendMessage` | 同步消息处理（返回 `A2aTask`） |
| POST | `/api/a2a/message/stream` | `A2aMessageController#streamMessage` | SSE 流式（streaming-disabled 时 405） |
| GET | `/api/a2a/tasks/{id}` | `A2aTaskController#getTask` | 查询 Task |
| POST | `/api/a2a/tasks/{id}/cancel` | `A2aTaskController#cancelTask` | 取消 Task（终态 409） |

---

## Webhooks（外部渠道回调）

来源：`com.lifepilot.interaction.channel.webhook.WebhookController`，Base Path: `/api/webhook`

| Method | Path | Handler | 备注 |
|---|---|---|---|
| GET | `/api/webhook/wecom` | `wecomVerify` | 企微 URL 验证（返回字符串） |
| POST | `/api/webhook/wecom` | `wecomMessage` | 企微消息接收（返回字符串） |
| POST | `/api/webhook/dingtalk` | `dingtalkMessage` | 钉钉消息接收（返回 Map） |
| POST | `/api/webhook/feishu` | `feishuEvent` | 飞书事件接收（返回 Map；异常时返回 `{\"code\":0}`） |

---

## MCP Server（服务端 JSON-RPC）

来源：`com.lifepilot.mcp.bridge.McpServerEndpoint`，Base Path: `/mcp`

> 注意：该端点 **不在** `/api` 前缀下，且仅在 `lifepilot.mcp.server.enabled=true` 时启用。

| Method | Path | Handler | 备注 |
|---|---|---|---|
| POST | `/mcp` | `handleRequest` | MCP Streamable HTTP(JSON-RPC)：initialize/tools/list/tools/call |

