# API 标准规范

本文档定义 LifePilot 项目前后端 API 交互的统一标准，作为后端实现与前端对齐的规范依据。

- **版本**: 1.0
- **更新日期**: 2026-02-28
- **API 前缀**: `/api`（除特别说明，如 `/mcp`）

## 目录

- [1. 基础规范](#1-基础规范)
- [2. URL 设计规范](#2-url-设计规范)
- [3. HTTP 方法与状态码](#3-http-方法与状态码)
- [4. 请求格式](#4-请求格式)
- [5. 响应格式](#5-响应格式)
- [6. 错误处理](#6-错误处理)
- [7. 分页规范](#7-分页规范)
- [8. 排序与筛选](#8-排序与筛选)
- [9. 数据格式规范](#9-数据格式规范)
- [10. 流式响应（SSE）](#10-流式响应sse)
- [11. 认证与授权](#11-认证与授权)
- [12. API 端点清单](#12-api-端点清单)
- [参考实现](#参考实现)

---

## 1. 基础规范

### 1.1 协议与编码

- **协议**: HTTPS（生产环境）
- **Content-Type**: `application/json`
- **字符编码**: UTF-8

### 1.2 Base URL（示例）

```
开发环境: http://localhost:8080
API 前缀: /api
```

### 1.3 标准请求头

```http
Accept: application/json
Content-Type: application/json
```

---

## 2. URL 设计规范

### 2.1 命名规范

- 使用小写字母与连字符（kebab-case）
- 资源集合使用复数名词
- 资源关系用层级表达，嵌套建议不超过 2 层

**正确示例**:

```
GET    /api/knowledge-bases
GET    /api/knowledge-bases/{id}
GET    /api/knowledge-bases/{id}/documents
POST   /api/knowledge-bases/{id}/documents
DELETE /api/knowledge-bases/{id}/documents/{docId}
```

### 2.2 非 CRUD 操作

对“动作”类操作，使用动词作为路径后缀（仍用 `POST` 表达执行）：

```
POST /api/workflows/{id}/enable
POST /api/workflows/{id}/disable
POST /api/workflows/{id}/trigger
POST /api/workflows/{id}/executions/{instanceId}/retry
POST /api/workflows/{id}/executions/{instanceId}/cancel
POST /api/chat/sessions/{id}/clear
```

---

## 3. HTTP 方法与状态码

### 3.1 方法语义

| 方法 | 用途 | 幂等性 | 备注 |
|------|------|--------|------|
| GET | 查询资源 | ✅ | 不应产生副作用 |
| POST | 创建资源/执行动作 | ❌ | 创建建议返回 201 |
| PUT | 全量更新 | ✅ | 完整替换 |
| PATCH | 部分更新 | ❌ | 局部修改 |
| DELETE | 删除资源 | ✅ | 成功建议返回 204 |

### 3.2 常用状态码

| 状态码 | 含义 | 典型场景 |
|--------|------|----------|
| 200 | OK | 查询/更新成功 |
| 201 | Created | 创建成功 |
| 202 | Accepted | 已接受异步处理（如文档导入） |
| 204 | No Content | 删除/动作成功（无响应体） |
| 400 | Bad Request | 参数错误/格式错误 |
| 401 | Unauthorized | 缺少或无效认证 |
| 403 | Forbidden | 已认证但无权限 |
| 404 | Not Found | 资源不存在 |
| 405 | Method Not Allowed | 端点不可用（如禁用 streaming） |
| 409 | Conflict | 状态冲突（如取消终态任务） |
| 500 | Internal Server Error | 未预期错误 |
| 503 | Service Unavailable | 能力未启用/依赖不可用 |

---

## 4. 请求格式

### 4.1 查询参数

- 命名建议用 `camelCase`（如 `timeFrom`, `minTokens`）
- 布尔值用 `true/false`
- 时间范围用 ISO 8601（见 [9.1 时间格式](#91-时间格式)）

示例：

```http
GET /api/traces?page=0&size=20&status=success&timeFrom=2026-02-01T00:00:00Z
```

### 4.2 路径参数

```http
GET /api/knowledge-bases/{id}
GET /api/workflows/{id}/executions/{instanceId}
```

### 4.3 JSON 请求体

字段命名使用 `camelCase`，必填字段不可为空：

```json
{
  "name": "知识库名称",
  "description": "描述信息",
  "embeddingModel": "text-embedding-3-small"
}
```

### 4.4 文件上传（multipart）

用于知识库文档上传：

```http
POST /api/knowledge-bases/{id}/documents
Content-Type: multipart/form-data
```

---

## 5. 响应格式

### 5.1 成功响应（JSON）

- 单资源：返回资源对象
- 列表：返回数组或分页结构（见 [7. 分页规范](#7-分页规范)）

### 5.2 下载响应

下载/预览类接口（如文档下载）使用合适的 `Content-Type` 与 `Content-Disposition`。

---

## 6. 错误处理

### 6.1 统一错误结构：`ErrorResponse`

后端统一错误响应体（与代码 `ErrorResponse` 对齐）：

```json
{
  "code": 404,
  "message": "知识库不存在: id=kb-123",
  "detail": "KnowledgeBaseNotFoundException",
  "traceId": "trace-abc-123",
  "timestamp": "2026-02-28T10:05:00Z"
}
```

- `detail` / `traceId` 可能为 `null`
- `traceId` 从日志 MDC 中读取（若存在）

### 6.2 全局异常处理

使用全局异常处理器将异常转为 `ErrorResponse`：

- 4xx：记录 `WARN`
- 5xx：记录 `ERROR`（含堆栈）

---

## 7. 分页规范

### 7.1 请求参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 0 | 页码（从 0 开始） |
| `size` | int | 20 | 每页大小（建议 10-100） |

### 7.2 响应结构

若后端返回分页结构，建议统一为：

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "total": 0
}
```

> 注意：部分现有接口可能使用 `content/totalPages` 等字段；如需进一步统一，可在后续重构中收敛。

---

## 8. 排序与筛选

### 8.1 排序

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `sortBy` | string | 资源特定 | 排序字段 |
| `order` | string | `desc/asc` 资源特定 | `asc` / `desc` |

### 8.2 筛选（通用模式）

- `q`：关键字搜索
- `status`：状态枚举
- `timeFrom` / `timeTo`：时间范围
- `minXxx` / `maxXxx`：数值范围

---

## 9. 数据格式规范

### 9.1 时间格式

统一使用 ISO 8601：

```
2026-02-28T10:00:00Z
2026-02-28T10:00:00+08:00
```

### 9.2 枚举值

枚举值建议使用小写字符串（除非协议要求）：

```json
{ "status": "success" }
```

### 9.3 可选字段

可选字段可省略或为 `null`。

---

## 10. 流式响应（SSE）

### 10.1 Chat SSE 端点

```http
POST /api/chat/messages/stream
Accept: text/event-stream
Content-Type: application/json
```

### 10.2 事件类型与 data 结构

> **注意**：事件类型定义参见后端常量类 `com.lifepilot.interaction.web.sse.SseEventType` 和前端常量 `@/constants/sseEvents`。

#### Chat 模块事件类型

| 事件类型 | 说明 | 数据结构 |
|---------|------|---------|
| `token` | 增量文本片段事件 | `{"content": "文本片段"}` |
| `ui` | UI 组件更新事件 | `{"components": [...]}` |
| `done` | 消息完成事件 | `{"messageId": "...", "content": "...", "timestamp": ..., "tokenUsage": {...}, "traceId": "..."}` |
| `error` | 错误事件 | `{"code": 500, "message": "...", "traceId": "..."}` |
| `heartbeat` | 心跳事件 | `""`（空字符串） |

**`done` 事件完整字段说明**：
- `messageId`（必填）：消息 ID
- `content`（可选）：完整消息内容（非流式响应时提供）
- `sessionId`（可选）：会话 ID
- `timestamp`（必填）：消息完成时间戳（毫秒）
- `tokenUsage`（可选）：Token 使用统计，格式：`{"promptTokens": 100, "completionTokens": 200, "totalTokens": 300, "modelId": "..."}`
- `traceId`（可选）：追踪 ID，用于调试和日志关联

**`error` 事件字段说明**：
- `code`（必填）：HTTP 状态码或错误码
- `message`（必填）：错误消息
- `traceId`（可选）：追踪 ID

#### A2A 模块事件类型

| 事件类型 | 说明 | 数据结构 |
|---------|------|---------|
| `task-status-update` | 任务状态更新事件 | `{"id": "...", "status": {...}, ...}` |
| `task-artifact-update` | 任务产物更新事件 | `{"id": "...", "artifacts": [...], ...}` |
| `task-complete` | 任务完成事件 | `{"id": "...", "status": {...}, ...}` |

**示例**：

```
event: token
data: {"content":"Hello"}

event: token
data: {"content":" World"}

event: ui
data: {"components":[{"type":"button","id":"btn-1","label":"确认"}]}

event: done
data: {"messageId":"msg-123","content":"Hello World","timestamp":1709107200000,"tokenUsage":{"promptTokens":10,"completionTokens":2,"totalTokens":12,"modelId":"gpt-4"},"traceId":"trace-abc-123"}

event: error
data: {"code":500,"message":"处理失败","traceId":"trace-abc-123"}

event: heartbeat
data: 
```

---

## 11. 认证与授权

### 11.1 API Key（A2A）

当服务端启用 API Key 校验时，对 `/api/a2a/**` 请求需要携带：

```http
X-API-Key: <your-api-key>
```

无效或缺失时返回 `401 Unauthorized`。

---

## 12. API 端点清单

项目当前所有后端 API 端点清单见：`docs/API_ENDPOINTS.md`。

---

## 参考实现

- **错误响应**: `src/main/java/com/lifepilot/interaction/web/model/ErrorResponse.java`
- **全局异常处理**: `src/main/java/com/lifepilot/interaction/web/controller/WebExceptionHandler.java`
- **Chat/SSE**: `src/main/java/com/lifepilot/interaction/web/controller/ChatController.java`
