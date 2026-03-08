# Web UI 架构设计

> **模块编号**：Phase 5 — 模块 18
> **依赖模块**：Gateway + Channel 适配器（模块 13）、Agent 引擎（模块 2）、LLM Router（模块 1）
> **最后更新**：2026-02-26

---

## 1. 模块定位与职责边界

Web UI 模块为 ZhiWei 提供浏览器端交互界面，是 Phase 5 的核心交付物。

**核心架构决策：前后端彻底分离。**

- **后端 API 层**（本 Java 项目内）：Spring Boot REST Controller + SSE 流式端点 + WebChannelAdapter，复用 MessageGateway 中间件管道。后端是纯 Server 端逻辑，不包含任何前端构建集成。
- **前端 SPA 层**（独立项目）：Vue 3 + Vite + Pinia 单页应用，独立仓库、独立构建、独立部署。通过 HTTP/SSE 调用后端 API。

这种分离为 Phase 6 的多端接入（CLI HTTP 客户端、移动端、桌面端）奠定基础，所有客户端共享同一套 REST/SSE API。

### 职责边界

| 属于本模块（后端） | 属于本模块（前端，独立项目） | 不属于本模块 |
|-------------------|--------------------------|------------|
| REST API 端点（对话、设置、信号） | Vue 3 SPA 应用框架 | 知识库管理页面（模块 19） |
| SSE 流式对话传输 | 对话页 + 设置页 | Skill/MCP 管理页面（模块 19） |
| WebChannelAdapter 通道适配器 | A2UI 渲染器 + 组件目录 | 轨迹回放页面（模块 19） |
| A2UI 后端数据模型 + 序列化 | 流式 Markdown 渲染 | 工作流管理页面（模块 19） |
| Web 配置属性 | 状态管理、路由 | CLI HTTP 客户端迁移（Phase 6） |
| CORS 配置 | SSE 客户端封装 | |

---

## 2. 核心概念与术语

| 术语 | 定义 |
|------|------|
| A2UI (Agent-to-UI) | Google 提出的声明式 Generative UI 协议（v0.8 Public Preview），Agent 以 JSON 描述 UI 组件树，客户端渲染为原生控件 |
| SSE (Server-Sent Events) | 服务端向客户端单向推送事件的 HTTP 协议，用于流式传输 LLM 生成的 Token 和 A2UI 组件描述 |
| SseEmitter | Spring MVC 提供的 SSE 发射器，支持异步逐块发送事件到客户端 |
| WebChannelAdapter | Web 通道适配器，实现 `ChannelAdapter` 接口，桥接 REST 请求与 MessageGateway |
| Pinia | Vue 3 官方状态管理库，管理对话列表、消息流、用户设置等全局状态 |
| shadcn-vue | 基于 Radix Vue 的 Vue 3 组件库，提供无样式（headless）UI 原语 + Tailwind CSS 样式 |

---

## 3. 架构设计

### 3.1 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│              前端独立项目 (lifepilot-web)                       │
│                      浏览器 (Vue 3 SPA)                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                    │
│  │ ChatView │  │ Settings │  │  Layout  │                    │
│  │ 对话页面  │  │ 设置页面  │  │ 布局框架  │                    │
│  └────┬─────┘  └────┬─────┘  └──────────┘                    │
│       │              │                                        │
│  ┌────┴──────────────┴──────────────────────┐                │
│  │  ┌─────────────────┐  ┌────────────────┐ │                │
│  │  │ StreamingText   │  │ A2uiRenderer   │ │                │
│  │  │ Markdown 渲染    │  │ Generative UI  │ │                │
│  │  └─────────────────┘  └────────────────┘ │                │
│  │     Pinia Store 层                        │                │
│  │  chatStore / settingsStore / a2uiStore    │                │
│  └────┬──────────────────────────────────────┘                │
│       │  fetch / ReadableStream (token + ui 事件)             │
└───────┼──────────────────────────────────────────────────────┘
        │ HTTP / SSE（跨域或同域反向代理）
┌───────┼──────────────────────────────────────────────────────┐
│       ▼              后端 (lifepilot Java 项目)                │
│  ┌─────────────────────┐                                     │
│  │  ChatController     │  REST + SSE 端点（token + ui 事件）  │
│  │  SettingsController │  设置 API                            │
│  └────┬────────────────┘                                     │
│       │                                                       │
│  ┌────▼────────────────┐                                     │
│  │ WebChannelAdapter   │  ChannelAdapter 实现                 │
│  └────┬────────────────┘                                     │
│       │                                                       │
│  ┌────▼────────────────┐                                     │
│  │  MessageGateway     │  已有中间件管道                       │
│  │  (Auth → RateLimit  │                                     │
│  │   → Security →      │                                     │
│  │   Router →          │                                     │
│  │   Execution →       │                                     │
│  │   Audit)            │                                     │
│  └─────────────────────┘                                     │
│              Spring Boot 后端                                 │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 后端 API 层设计

#### 3.2.1 REST 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/messages` | 发送消息（非流式，返回完整响应） |
| POST | `/api/chat/messages/stream` | 发送消息（SSE 流式响应） |
| GET | `/api/chat/sessions` | 获取会话列表 |
| GET | `/api/chat/sessions/{id}/messages` | 获取会话历史消息 |
| DELETE | `/api/chat/sessions/{id}` | 删除会话 |
| POST | `/api/chat/signals` | A2UI 信号回传（用户与 Generative UI 组件交互） |
| GET | `/api/settings` | 获取用户设置 |
| PUT | `/api/settings` | 更新用户设置 |
| GET | `/api/health` | 健康检查（复用 Actuator） |

#### 3.2.2 SSE 流式协议

SSE 端点返回 `text/event-stream`，事件格式：

```
event: token
data: {"content": "你好", "index": 0}

event: token
data: {"content": "，我是", "index": 1}

event: ui
data: {"components": [{"id": "c1", "type": "Card", "properties": {"title": "今日待办"}, "children": ["c2"]}, {"id": "c2", "type": "Text", "properties": {"value": "完成报告"}}]}

event: done
data: {"messageId": "uuid", "tokenUsage": {"input": 100, "output": 50}}

event: error
data: {"code": 500, "message": "LLM 服务不可用"}
```

事件类型：
- `token`：LLM 生成的增量文本片段
- `ui`：A2UI 组件描述 JSON，前端 A2uiRenderer 接收后渲染为原生 Vue 组件
- `done`：流式传输完成，携带完整消息 ID 和 Token 统计
- `error`：处理过程中发生错误

#### 3.2.3 WebChannelAdapter

```java
// 实现 ChannelAdapter 接口，桥接 REST 请求与 MessageGateway
public class WebChannelAdapter extends AbstractChannelAdapter {
    // channelType() → ChannelType.WEB
    // normalize() → 将 REST 请求体转换为 GatewayMessage
    // sendResponse() → 通过 SseEmitter 推送响应（流式场景）
}
```

WebChannelAdapter 的核心职责：
1. 将 HTTP 请求转换为 `GatewayMessage`（`channelType = WEB`）
2. 调用 `MessageGateway.process()` 走完整中间件管道
3. 流式场景下，通过 `SseEmitter` 逐 Token 推送响应

#### 3.2.4 SSE 实现方案选型

| 方案 | 优势 | 劣势 | 结论 |
|------|------|------|------|
| Spring MVC `SseEmitter` | 与现有 Servlet 栈一致、简单直接 | 需手动管理超时和异常 | ✅ 采用 |
| Spring WebFlux `Flux<ServerSentEvent>` | 响应式原生支持 | 需引入 WebFlux 依赖，与现有 MVC 栈冲突 | ❌ 不采用 |
| WebSocket | 双向通信 | AI 对话场景只需服务端→客户端单向推送，过度设计 | ❌ 不采用 |

选择 `SseEmitter` 的理由：ZhiWei 已使用 Spring MVC（`spring-boot-starter-web`），SSE 是 AI 对话流式响应的行业标准方案（OpenAI、DeepSeek、通义千问等均采用 SSE），且 `SseEmitter` 在 Virtual Thread 环境下表现良好。

#### 3.2.5 CORS 配置

前后端分离部署，前端和后端运行在不同端口/域名，必须配置 CORS：

```java
// WebAutoConfiguration 中注册 CORS 配置
@Bean
public WebMvcConfigurer corsConfigurer(WebProperties properties) {
    return new WebMvcConfigurer() {
        @Override
        public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/api/**")
                    .allowedOrigins(properties.getCors().getAllowedOrigins())
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true);
        }
    };
}
```

配置项：
- `lifepilot.web.cors.allowed-origins`：允许的前端源（开发时 `http://localhost:5173`，生产时实际域名）
- `lifepilot.web.cors.allow-credentials`：是否允许携带 Cookie（Session 认证需要）

### 3.3 前端 SPA 层设计（独立项目）

前端作为独立项目（`lifepilot-web`），不在 Java 项目内，拥有独立的 `package.json`、构建流程和部署方式。

#### 3.3.1 技术选型

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.5+ | 响应式 UI 框架，Composition API |
| Vite | 6.x | 开发服务器 + 构建工具 |
| Pinia | 3.x | 状态管理 |
| Vue Router | 4.x | 前端路由 |
| shadcn-vue | latest | UI 组件库（基于 Radix Vue + Tailwind CSS） |
| Tailwind CSS | 4.x | 原子化 CSS |
| vue-markdown-renderer | latest | AI 流式 Markdown 渲染（高性能增量 DOM 更新） |
| TypeScript | 5.x | 类型安全 |

#### 3.3.2 前端项目结构

```
lifepilot-web/                  # 独立前端项目根目录
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.ts                 # 入口
│   ├── App.vue                 # 根组件
│   ├── router/
│   │   └── index.ts            # 路由配置
│   ├── stores/
│   │   ├── chat.ts             # 对话状态
│   │   ├── a2ui.ts             # A2UI 组件树状态
│   │   └── settings.ts         # 设置状态
│   ├── views/
│   │   ├── ChatView.vue        # 对话页
│   │   └── SettingsView.vue    # 设置页
│   ├── components/
│   │   ├── chat/
│   │   │   ├── MessageList.vue     # 消息列表
│   │   │   ├── MessageBubble.vue   # 消息气泡
│   │   │   ├── ChatInput.vue       # 输入框
│   │   │   └── StreamingText.vue   # 流式文本渲染
│   │   ├── layout/
│   │   │   ├── AppLayout.vue       # 应用布局
│   │   │   └── Sidebar.vue         # 侧边栏
│   │   ├── a2ui/
│   │   │   ├── A2uiRenderer.vue    # A2UI 递归渲染器
│   │   │   ├── A2uiText.vue        # 文本组件
│   │   │   ├── A2uiCard.vue        # 卡片组件
│   │   │   ├── A2uiButton.vue      # 按钮组件
│   │   │   ├── A2uiList.vue        # 列表组件
│   │   │   └── componentCatalog.ts # 组件目录注册表
│   │   └── ui/                     # shadcn-vue 组件
│   ├── composables/
│   │   ├── useChat.ts          # 对话 composable（SSE 连接管理）
│   │   ├── useA2uiSignal.ts    # A2UI 信号 composable
│   │   └── useSettings.ts      # 设置 composable
│   ├── api/
│   │   └── client.ts           # HTTP/SSE 客户端封装
│   ├── types/
│   │   └── index.ts            # TypeScript 类型定义
│   └── assets/
│       └── styles/
│           └── main.css        # 全局样式 + Tailwind 入口
└── public/
    └── favicon.ico
```

#### 3.3.3 API 基地址配置

前端通过环境变量配置后端 API 地址，支持开发和生产环境切换：

```typescript
// api/client.ts
const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080'
```

开发时 Vite 可配置代理（可选），也可直接跨域访问后端（后端已配置 CORS）：

```typescript
// vite.config.ts
export default defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

#### 3.3.4 状态管理设计

```typescript
// chatStore — 管理对话状态
interface ChatState {
  sessions: ChatSession[]        // 会话列表
  activeSessionId: string | null // 当前活跃会话
  messages: Message[]            // 当前会话消息
  isStreaming: boolean           // 是否正在流式接收
  streamingContent: string       // 流式接收中的内容缓冲
}

// settingsStore — 管理用户设置
interface SettingsState {
  theme: 'light' | 'dark' | 'system'
  language: string
  llmProvider: string
}
```

#### 3.3.5 SSE 客户端实现

前端通过 `fetch` + `ReadableStream` 消费 SSE 流：

```typescript
// useChat composable 核心逻辑
async function sendMessage(content: string, sessionId?: string, attachmentIds?: string[]) {
  // 1. 添加用户消息到本地状态
  // 2. fetch POST /api/chat/messages/stream，流式读取响应体
  // 3. 解析 SSE 事件，token 事件增量拼接到 streamingContent
  // 4. ui 事件路由到 a2uiStore.updateComponents()
  // 5. done 事件将完整消息存入 messages
  // 6. error 事件显示错误提示
}
```

请求体（JSON）示例：

```json
{
  "content": "帮我总结这张截图的要点",
  "sessionId": "810233ba-2a45-450a-926e-79be3f5c6925",
  "attachmentIds": ["att-1"]
}
```

补充说明：

- **多模态**：附件需先通过 `POST /api/chat/messages/upload` 上传，拿到 `attachmentId` 后放入 `attachmentIds`。
- **知识库关联（RAG）**：知识库不是每条消息都传参，而是通过 `PATCH /api/chat/sessions/{id}/config` 将 `knowledgeBaseIds` 绑定到会话；后端按 `sessionId` 自动检索并注入“知识库片段”。

选择 `fetch` + `ReadableStream` 而非 `EventSource` 的理由：
- `EventSource` 只支持 GET 请求，无法在请求体中携带消息内容
- `fetch` 支持 POST 请求 + 流式读取响应体
- 可自定义请求头（如 Session ID、认证 Token）

#### 3.3.6 流式 Markdown 渲染与调试视图

选型对比：

| 方案 | 优势 | 劣势 |
|------|------|------|
| vue-markdown-renderer | Vue 3 原生、增量 DOM 更新、支持 Mermaid/KaTeX、性能极优 | 较新项目 |
| markdown-it + 手动渲染 | 成熟稳定 | 流式场景需自行处理增量更新，性能差 |
| marked + DOMPurify | 轻量 | 同上，且安全处理需额外配置 |

采用 `vue-markdown-renderer`：专为 AI 流式场景设计，最小化 DOM 更新，支持代码高亮、Mermaid 图表渐进渲染，Vue 3 生态原生支持。

在此基础上，ChatView 顶部与右侧调试抽屉会消费 `done` 事件中的调试字段：

- `tokenUsage` / `usage`：更新“模型与 Token”摘要卡片
- `reasoningSummary` + `reasoning` 事件流：构建“推理过程时间线”
- `sources`：展示本轮命中的知识库 / 文档来源列表，帮助用户确认 RAG 是否命中预期知识
- `toolsSummary`：展示本轮调用的工具列表及耗时，支持从消息跳转到 Trace 详情

#### 3.3.7 部署方式

前端独立部署，常见方案：
- **开发环境**：`npm run dev`（Vite 开发服务器，端口 5173），通过代理或 CORS 访问后端
- **生产环境**：`npm run build` 生成静态文件，通过以下任一方式部署：
  - Nginx / Caddy 反向代理（推荐，同时代理 `/api` 到后端）
  - 任意静态文件服务器（如 `npx serve dist`）
  - CDN 托管

---

## 4. A2UI Generative UI 架构

### 4.1 A2UI 协议概述

A2UI（Agent-to-UI）是 Google 提出的声明式 Generative UI 协议（v0.8 Public Preview），核心思想是 Agent 以结构化 JSON 描述 UI 组件树，客户端使用原生控件渲染。与传统模板渲染不同，A2UI 让 LLM 动态生成 UI 而非仅生成文本。

协议核心模型：
- **邻接表（Adjacency List）**：组件树以扁平数组表示，每个节点通过 `children` 引用子节点 ID
- **组件目录（Component Catalog）**：客户端声明支持的组件类型及其属性 schema，Agent 只能使用目录中的组件
- **信号（Signal）**：用户交互（点击按钮、输入文本等）通过信号回传给 Agent，触发后续推理

### 4.2 A2UI 数据模型

后端定义 A2UI 数据模型（Java record），序列化为 JSON 通过 SSE `ui` 事件推送：

```json
{
  "components": [
    {
      "id": "c1",
      "type": "Card",
      "properties": { "title": "今日待办", "variant": "outlined" },
      "children": ["c2", "c3"]
    },
    {
      "id": "c2",
      "type": "Text",
      "properties": { "value": "完成项目报告" }
    },
    {
      "id": "c3",
      "type": "Button",
      "properties": { "label": "标记完成", "variant": "primary" },
      "signal": { "name": "todo.complete", "payload": { "todoId": "123" } }
    }
  ]
}
```

### 4.3 Vue A2UI 渲染器设计（前端项目）

Vue 3 的 `<component :is>` 动态组件机制天然适合实现 A2UI 渲染器：

```
A2UI JSON → a2uiStore (Pinia) → A2uiRenderer.vue → <component :is> → 原生 Vue 组件
                                       ↑
                              组件目录注册表
                          (componentCatalog.ts)
```

核心组件：

| 组件 | 职责 |
|------|------|
| `A2uiRenderer.vue` | 递归渲染 A2UI 组件树，根据 `type` 查找组件目录，通过 `<component :is>` 动态渲染 |
| `componentCatalog.ts` | 组件目录注册表，映射 A2UI type → Vue 组件，声明属性 schema |
| `a2uiStore` (Pinia) | 管理 A2UI 组件树状态，处理 SSE `ui` 事件的增量更新 |
| `useA2uiSignal.ts` | 信号 composable，将用户交互封装为信号发送回后端 |

### 4.4 组件目录（Phase 5 初始集合）

| A2UI Type | Vue 组件 | 用途 |
|-----------|---------|------|
| `Text` | `A2uiText.vue` | 纯文本 / Markdown 文本 |
| `Card` | `A2uiCard.vue` | 卡片容器（标题 + 内容 + 操作） |
| `Button` | `A2uiButton.vue` | 操作按钮，触发信号 |
| `TextField` | `A2uiTextField.vue` | 文本输入框 |
| `List` | `A2uiList.vue` | 列表（待办、日程等） |
| `ListItem` | `A2uiListItem.vue` | 列表项 |
| `DatePicker` | `A2uiDatePicker.vue` | 日期选择器 |
| `Chip` | `A2uiChip.vue` | 标签/状态标记 |
| `Divider` | `A2uiDivider.vue` | 分隔线 |
| `Image` | `A2uiImage.vue` | 图片展示 |

组件目录可扩展：后续模块（模块 19）可注册更多组件类型（Chart、Table、CodeBlock 等）。

### 4.5 A2UI 信号流

用户与 A2UI 组件交互时，信号通过 REST API 回传给 Agent：

```
用户点击按钮 → A2uiButton 触发 signal
    → useA2uiSignal composable
    → POST /api/chat/signals { name: "todo.complete", payload: { todoId: "123" }, sessionId: "..." }
    → ChatController → WebChannelAdapter → MessageGateway
    → Agent 处理信号 → 返回新的文本/A2UI 响应
```

### 4.6 A2UI 与流式文本的混合渲染

一条 Agent 响应可以同时包含文本 Token 和 A2UI 组件。SSE 流中 `token` 事件和 `ui` 事件交替出现，前端分别路由到 StreamingText 和 A2uiRenderer：

```
SSE 流:
  event: token → chatStore.streamingContent += content
  event: token → chatStore.streamingContent += content
  event: ui    → a2uiStore.updateComponents(components)
  event: token → chatStore.streamingContent += content
  event: done  → 最终消息包含 text + a2ui components
```

消息数据模型扩展：

```typescript
interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string           // 文本内容（Markdown）
  a2uiComponents?: A2uiComponent[]  // A2UI 组件树（可选）
  timestamp: number
}
```

---

## 5. 关键设计决策

| # | 决策 | 备选方案 | 选择理由 |
|---|------|---------|---------|
| 1 | 前后端彻底分离 | frontend-maven-plugin 打包进 JAR | 后端纯 Server 逻辑，为 Phase 6 多端接入（CLI HTTP、移动端）奠定基础；前端独立构建部署，开发体验更好 |
| 2 | SSE 流式传输（SseEmitter） | WebSocket / WebFlux Flux | SSE 是 AI 对话流式响应的行业标准；与现有 Spring MVC 栈一致；单向推送足够 |
| 3 | Vue 3 + shadcn-vue | React + shadcn/ui | Phase 5 评估结论：中文社区生态强、Vercel AI SDK v6 支持 Vue composables、生态成熟 |
| 4 | vue-markdown-renderer | markdown-it / marked | 专为 AI 流式 Markdown 设计，增量 DOM 更新性能极优，Vue 3 原生支持 |
| 5 | fetch + ReadableStream | EventSource API | EventSource 只支持 GET，无法携带 POST 请求体；fetch 支持自定义请求头和 POST |
| 6 | Pinia 状态管理 | Vuex / 组件本地状态 | Vue 3 官方推荐、TypeScript 友好、Composition API 原生支持 |
| 7 | Tailwind CSS 4 | 传统 CSS / CSS Modules | 原子化 CSS 开发效率高、与 shadcn-vue 天然配合、构建产物体积小 |
| 8 | A2UI 协议 + 自建 Vue 渲染器 | 纯文本 SSE / 自定义 JSON 协议 | A2UI 是 Google 标准化方案（v0.8），邻接表模型简洁；Vue `<component :is>` 天然适配；组件目录可扩展 |
| 9 | A2UI 组件内嵌 SSE 流 | 独立 WebSocket 通道 / 轮询 | 复用已有 SSE 通道，`ui` 事件与 `token` 事件交替传输，无需额外连接 |
| 10 | 后端 CORS 配置 | Nginx 反向代理统一入口 | 开发阶段最简方案；生产环境可选择 Nginx 代理替代 CORS |

---

## 6. 与已有模块的集成点

### 6.1 MessageGateway 集成

Web UI 的 REST Controller 通过 `WebChannelAdapter` 接入 `MessageGateway`，复用完整的中间件管道（Auth → RateLimit → Security → Router → Execution → Audit）。这意味着 Web UI 自动获得：

- 认证鉴权（Session 模式，`lifepilot.gateway.auth.web.session`）
- 请求限流
- 安全检查（Prompt 注入检测、敏感数据过滤）
- 智能路由（快速路径命令直接分发）
- 审计日志

### 6.2 ChannelType.WEB

`ChannelType` 枚举已包含 `WEB("web", false)`，无需修改。`WebChannelAdapter` 实现 `ChannelAdapter` 接口，注册到 `MessageGateway`。

### 6.3 ResponseContent 流式支持

`ResponseContent.StreamingContent(streamId)` 已定义，用于标识流式传输通道。SSE 端点在收到 `StreamingContent` 响应时，通过 `streamId` 关联 `SseEmitter`，逐 Token 推送内容。

### 6.4 配置集成

Web 通道配置已在 `application.yml` 中预留：

```yaml
lifepilot:
  gateway:
    channels:
      web:
        enabled: false  # Phase 5 实现后改为 true
```

---

## 7. 安全设计

### 7.1 认证方案

Phase 5 采用 Session 认证（已在 Gateway Auth 中间件中实现）：

- 本地部署场景：默认信任 localhost 请求，无需登录
- 远程访问场景：简单密码认证 + Session Cookie
- 前后端分离场景下，Session Cookie 通过 CORS `allowCredentials: true` 跨域传递

### 7.2 CORS 配置

前后端分离部署，必须配置 CORS：

- 开发环境：后端允许 `http://localhost:5173`（Vite 默认端口）
- 生产环境：配置实际前端域名，或通过 Nginx 反向代理统一入口（此时无需 CORS）
- 配置项：`lifepilot.web.cors.allowed-origins`、`lifepilot.web.cors.allow-credentials`

### 7.3 XSS 防护

- Markdown 渲染使用 `vue-markdown-renderer` 内置的 HTML 净化
- 用户输入在后端经过 Security 中间件的敏感数据过滤

---

## 8. 调研参考

### 8.1 开源项目参考

| 项目 | Stars | 技术栈 | 借鉴点 |
|------|-------|--------|--------|
| [Open WebUI](https://github.com/open-webui/open-webui) | 80k+ | SvelteKit + FastAPI | 对话 UI 交互模式、会话管理、流式渲染架构、前后端分离部署 |
| [LobeChat](https://lobehub.com) | 70k+ | React + Next.js | 多模型切换 UI、对话分支、插件系统 UI |
| [Chatbox](https://github.com/Bin-Huang/chatbox) | 25k+ | Electron + React | 桌面端 AI 对话 UI、多 Provider 配置界面 |
| [shadcn-vue](https://github.com/unovue/shadcn-vue) | 5k+ | Vue 3 + Radix Vue | 无样式组件库、Tailwind CSS 集成模式 |
| [vue-markdown-renderer](https://github.com/Simon-He95/vue-markdown-renderer) | 新兴 | Vue 3 | AI 流式 Markdown 增量渲染 |

### 8.2 技术方案参考

| 方案 | 来源 | 借鉴点 |
|------|------|--------|
| Spring Boot SSE + SseEmitter | [Baeldung: Spring MVC SSE](https://www.baeldung.com/spring-mvc-sse-streams) | SseEmitter 超时管理、异常处理模式 |
| Vercel AI SDK Vue composables | [AI SDK Docs](https://sdk.vercel.ai/docs/reference/ai-sdk-ui/use-chat) | useChat composable 设计模式、SSE 流协议格式 |
| AI 对话流式最佳实践 | [proagenticworkflows.ai](https://proagenticworkflows.ai/best-practices-streaming-llm-responses-front-end-stack) | SSE vs WebSocket vs fetch streaming 选型分析 |
| Google A2UI 协议 | [Google A2UI Spec](https://github.com/anthropics/a2ui) | 声明式 Generative UI 协议，邻接表组件树模型，组件目录 + 信号机制 |

> 以上参考来源均为 2024-2026 年发表的技术文章和开源项目，内容已重新组织表述以符合许可要求。

### 8.3 竞品深度分析

#### 8.3.1 OpenClaw（180k+ Stars，2026 年 1 月爆发）

**项目定位**：自托管 AI Agent 平台，强调「Agent 即操作系统」，通过 Gateway 架构统一接入 13+ 消息平台。

**技术栈**：
- 后端：Node.js 22 + TypeScript，单进程 Gateway（WebSocket 控制平面）
- Web UI：Lit-based Web Components，直接由 Gateway 进程提供服务（内嵌于 Gateway，非独立前端项目）
- 桌面端：Swift macOS 菜单栏应用（内嵌 WebKit 渲染 WebChat）
- 移动端：iOS/Android 作为 WebSocket Node 连接 Gateway
- 存储：文件系统（JSON session 文件）+ SQLite-vec（记忆向量检索）

**架构特点**：
- Hub-and-spoke 架构：单一 Gateway 作为控制平面，所有客户端（CLI / Web UI / 消息平台 / 移动端）通过 WebSocket 连接
- Web UI 不是独立前端项目，而是 Gateway 内嵌的 Lit Web Components，访问 `http://127.0.0.1:18789/` 即可使用
- Canvas + A2UI：Agent 生成 HTML + `a2ui-*` 属性，Canvas Server（独立进程，端口 18793）通过 WebSocket 推送到浏览器渲染
- 多 Agent 路由：不同 Channel/Group 可路由到不同 Agent 实例（独立 workspace、模型、行为）
- 插件系统：Channel / Tool / Memory / Provider 四类插件，基于 `package.json` 声明式发现

**Generative UI 方案**：
- A2UI 属性嵌入 HTML：`<button a2ui-action="complete" a2ui-param-id="123">`
- 用户交互通过 Canvas Server 转发为 Tool Call 回传 Agent
- 与 Google A2UI 协议不同：OpenClaw 的 A2UI 是 HTML 属性标注方案，Google A2UI 是 JSON 邻接表组件树方案

**对 ZhiWei 的借鉴**：
- Gateway 统一入口 + Channel 适配器模式（ZhiWei 已采用类似架构）
- Canvas 独立进程隔离思路（A2UI 渲染与主服务分离，崩溃不影响核心对话）
- 多 Agent 路由按 Channel/Session 分发（ZhiWei Phase 6 多 Agent 协作可参考）

**与 ZhiWei 的差异**：
- OpenClaw Web UI 内嵌于 Gateway，非独立前端项目；ZhiWei 采用前后端彻底分离
- OpenClaw 使用 Lit Web Components；ZhiWei 使用 Vue 3 + shadcn-vue
- OpenClaw A2UI 是 HTML 属性方案；ZhiWei 采用 Google A2UI JSON 协议（更标准化、更易扩展）

> 参考来源：[OpenClaw Architecture Overview](https://ppaolo.substack.com/p/openclaw-system-architecture-overview)、[OpenClaw Deployment Architectures](https://flowzap.xyz/blog/every-way-to-deploy-openclaw)、[OpenClaw Source Code Review](https://www.moely.ai/resources/openclaw-framework-source-code-review)。内容已重新组织表述。

#### 8.3.2 Open WebUI（80k+ Stars）

**项目定位**：自托管 AI 对话平台，兼容 Ollama 和 OpenAI 协议，强调离线可用和功能丰富。

**技术栈**：
- 前端：SvelteKit + Vite + TypeScript + TailwindCSS
- 后端：Python FastAPI，模块化路由架构
- 实时通信：WebSocket（Socket.IO）用于事件广播 + SSE 用于 LLM 流式响应
- 状态管理：Svelte Stores（响应式 store 模式）
- 数据库：SQLite / PostgreSQL（通过 SQLAlchemy ORM）
- 富文本编辑：TipTap 编辑器（支持协作编辑）

**架构特点**：
- 前后端同仓库但分层清晰：`src/` 为 SvelteKit 前端，`backend/` 为 FastAPI 后端
- 代理架构（Proxy-based）：前端不直接调用 LLM API，所有请求经后端代理转发，增强安全性和离线能力
- 中间件管道：FastAPI 中间件处理认证、RBAC、请求路由、工具执行
- 消息树结构：支持对话分支（Message History Tree），每条消息可有多个子回复
- RAG 知识系统：文档摄入 → 分块 → 嵌入 → 向量检索 → 重排序，完整 RAG 管线
- Pipeline 系统：可插拔的处理管线（类似 ZhiWei 的中间件管道）

**流式响应方案**：
- LLM 响应使用 SSE（Server-Sent Events）流式传输
- WebSocket 用于系统事件广播（用户状态、通知等），非 LLM 流式响应
- 支持多模型并行对话（Multi-Model Response Display）

**对 ZhiWei 的借鉴**：
- 消息树结构（对话分支）是高级对话 UI 的标配，ZhiWei 可在后续版本考虑
- 代理架构（前端不直接调用 LLM）与 ZhiWei 的 Gateway 中间件管道理念一致
- TipTap 富文本编辑器集成方案（ZhiWei 可在模块 19 考虑）
- WebSocket + SSE 混合方案：WebSocket 用于系统事件，SSE 用于 LLM 流式响应（ZhiWei Phase 5 先用纯 SSE，后续可扩展 WebSocket）

**与 ZhiWei 的差异**：
- Open WebUI 前后端同仓库（SvelteKit 构建后由 FastAPI 提供静态文件）；ZhiWei 前后端彻底分离
- Open WebUI 使用 Python FastAPI；ZhiWei 使用 Java Spring Boot
- Open WebUI 无 Generative UI 能力；ZhiWei 内置 A2UI 协议支持

> 参考来源：[Open WebUI Architecture](https://deepwiki.com/open-webui/open-webui/2-architecture)、[Open WebUI Frontend Architecture](https://deepwiki.com/open-webui/open-webui/2.1-frontend-architecture)、[Open WebUI Backend Architecture](https://deepwiki.com/open-webui/open-webui/2.2-backend-architecture)。内容已重新组织表述。

#### 8.3.3 LobeChat（70k+ Stars）

**项目定位**：开源 AI Agent 工作空间，支持多模型、知识库、MCP 市场、Artifacts 和 Thinking UI。

**技术栈**：
- 框架：Next.js 16 + React 19（Monorepo 架构，`@lobechat/` 命名空间）
- 组件库：Ant Design（基础组件）+ `@lobehub/ui`（AI 业务组件库，基于 antd 扩展）
- 状态管理：zustand（轻量级 Flux 模式）
- 数据获取：SWR（客户端数据获取）
- 路由：混合路由 — Next.js App Router（静态页面）+ React Router DOM（主 SPA）
- API：tRPC（端到端类型安全 API 通信）
- 数据库：Drizzle ORM + PostgreSQL
- 样式：antd-style（CSS-in-JS）
- 桌面端：Electron（`apps/desktop/`）
- 测试：Vitest（单元测试）+ Cucumber + Playwright（E2E 测试）

**架构特点**：
- Monorepo 架构：`packages/` 下共享包（database / agent-runtime / model-runtime 等），`src/` 为主应用
- `@lobehub/ui` 专用 AI 组件库：ChatItem、Markdown 渲染、代码高亮等 AI 对话场景专用组件
- Artifacts 系统：Agent 可生成可交互的代码片段/可视化内容（类似 Claude Artifacts）
- Thinking UI：展示 Agent 推理过程的专用 UI 组件
- MCP 市场：一键安装 MCP 工具，插件生态丰富
- 多模型聚合：统一接口对接 OpenAI / Anthropic / Google / Ollama 等多个 Provider
- 国际化：react-i18next，CI 自动生成翻译文件

**Generative UI 方案**：
- Artifacts：Agent 生成 React 组件代码，在沙箱中渲染（类似 Claude Artifacts）
- 非声明式协议，而是代码生成 + 沙箱执行模式
- 与 A2UI 的 JSON 声明式方案有本质区别

**对 ZhiWei 的借鉴**：
- `@lobehub/ui` 专用 AI 组件库的思路：ZhiWei 可将 A2UI 组件封装为可复用的 Vue 组件库
- Thinking UI（推理过程展示）：ZhiWei 可在 SSE 流中增加 `thinking` 事件类型
- zustand 的轻量状态管理理念与 Pinia 类似，验证了 ZhiWei 选择 Pinia 的合理性
- tRPC 端到端类型安全：ZhiWei 前后端分离场景下可考虑 OpenAPI 生成 TypeScript 客户端实现类似效果

**与 ZhiWei 的差异**：
- LobeChat 使用 React + Next.js（SSR/SSG）；ZhiWei 使用 Vue 3 + Vite（纯 SPA）
- LobeChat 使用 PostgreSQL；ZhiWei 使用 SQLite（单 JAR 部署）
- LobeChat Artifacts 是代码生成模式；ZhiWei A2UI 是声明式 JSON 协议（更轻量、更安全）
- LobeChat 是 Monorepo 全栈项目；ZhiWei 前后端彻底分离

> 参考来源：[LobeChat Development Guide](https://lobehub.com/docs/development/start)、[LobeChat GitHub](https://github.com/lobehub/lobe-chat)、[@lobehub/ui](https://github.com/lobehub/lobe-ui)。内容已重新组织表述。

#### 8.3.4 竞品对比矩阵

| 维度 | OpenClaw | Open WebUI | LobeChat | ZhiWei（规划） |
|------|----------|------------|----------|-----------------|
| **前端框架** | Lit Web Components | SvelteKit | React + Next.js | Vue 3 + Vite |
| **后端框架** | Node.js (Gateway) | Python FastAPI | Next.js API + tRPC | Java Spring Boot |
| **前后端分离** | 内嵌于 Gateway | 同仓库分层 | Monorepo 全栈 | 彻底分离（独立项目） |
| **组件库** | 自建 Lit 组件 | TailwindCSS 原子组件 | Ant Design + @lobehub/ui | shadcn-vue + Tailwind |
| **状态管理** | 无（Web Components 内部状态） | Svelte Stores | zustand | Pinia |
| **流式响应** | WebSocket 推送 | SSE（LLM）+ WebSocket（事件） | SSE | SSE（SseEmitter） |
| **Generative UI** | A2UI HTML 属性 + Canvas | 无 | Artifacts（代码沙箱） | A2UI JSON 协议 |
| **多通道接入** | 13+ 消息平台 | 仅 Web | 仅 Web + Electron 桌面 | Web + CLI + 企微/钉钉/飞书/Telegram |
| **数据库** | 文件系统 + SQLite-vec | SQLite / PostgreSQL | PostgreSQL | SQLite |
| **部署模式** | 单进程 Gateway | Docker 容器 | Docker / Vercel | 单 JAR + 独立前端 |

#### 8.3.5 竞品分析结论

1. **前后端分离是趋势**：OpenClaw 内嵌 Web UI 的方案虽然部署简单，但限制了前端独立演进。Open WebUI 和 LobeChat 虽然同仓库，但前后端分层清晰。ZhiWei 的彻底分离方案最为灵活，为多端接入奠定基础。

2. **Generative UI 差异化明显**：三个竞品中只有 OpenClaw 有 Generative UI 能力（HTML 属性方案），LobeChat 的 Artifacts 是代码沙箱模式。ZhiWei 采用 Google A2UI JSON 协议是更标准化、更安全的方案，这是重要的差异化优势。

3. **SSE 是 AI 流式响应的行业共识**：Open WebUI 和 LobeChat 均使用 SSE 传输 LLM 响应，OpenClaw 使用 WebSocket（因其 Gateway 架构天然基于 WebSocket）。ZhiWei 选择 SSE 符合行业主流。

4. **专用 AI 组件库值得借鉴**：LobeChat 的 `@lobehub/ui` 证明了 AI 对话场景需要专用组件（ChatItem、Markdown 渲染、Thinking UI 等）。ZhiWei 的 A2UI 组件目录 + shadcn-vue 基础组件可以形成类似的专用组件体系。
